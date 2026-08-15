import Foundation
import MoqCore
import MoqFormat
import MoqImport

/// The transport-neutral `.moqproj` authoring surface. `MoqMCP` and `moq-format`'s JSON-RPC
/// adapter both wrap this instead of each re-implementing project mutation and validation —
/// they differ only in how a call arrives and how a result is framed.
///
/// Every stateful operation takes a session `handle` from `Sessions.open()`. `validateProject`
/// additionally has a stateless overload that takes a whole `MoqProject` value directly, for a
/// caller (Studio) validating an edited, unsaved project that has no session at all.
public struct MoqService: Sendable {
    private let sessions = Sessions()
    private let allowNetworkImport: Bool

    /// - Parameter allowNetworkImport: gates `importOpenAPI` fetching from an `http(s)://` source.
    ///   Off by default — the process embedding this service decides whether outbound network
    ///   access during import is acceptable in its environment.
    public init(allowNetworkImport: Bool = false) {
        self.allowNetworkImport = allowNetworkImport
    }

    // MARK: - Sessions

    public func openSession() async -> String {
        await sessions.open()
    }

    @discardableResult
    public func closeSession(_ handle: String) async -> Bool {
        await sessions.close(handle)
    }

    private func session(_ handle: String) async throws -> ProjectSession {
        try await sessions.session(handle)
    }

    // MARK: - Project lifecycle

    public func createProject(
        handle: String, manifest: ProjectManifest, path: String, force: Bool
    ) async throws -> ProjectDescription {
        let session = try await session(handle)
        try await session.create(manifest: manifest, at: path, force: force)
        return try await describeProject(handle: handle)
    }

    public func openProject(handle: String, path: String, force: Bool) async throws -> ProjectDescription {
        let session = try await session(handle)
        try await session.open(path: path, force: force)
        return try await describeProject(handle: handle)
    }

    public func describeProject(handle: String) async throws -> ProjectDescription {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject
        let dirty = await session.isDirty
        return ProjectDescription(
            name: project.manifest.name, description: project.manifest.description, path: project.projectPath,
            endpointCount: project.endpoints.count, dirty: dirty)
    }

    public func closeProject(handle: String) async {
        await sessions.close(handle)
    }

    /// The full in-memory project currently open in `handle`. For resource/read-model exposure
    /// (e.g. an MCP `moq://project/current` resource) that needs the whole document, not the
    /// summary `describeProject` returns.
    public func projectSnapshot(handle: String) async throws -> MoqProject {
        let session = try await session(handle)
        let store = try await session.currentStore()
        return await store.currentProject
    }

    // MARK: - Endpoint listing / reading

    public func listEndpoints(handle: String, filter: EndpointFilter) async throws -> [EndpointSummary] {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject

        let filtered = project.endpoints.filter { endpoint in
            if let filterPath = filter.filterPath, endpoint.path != filterPath { return false }
            if let filterMethod = filter.filterMethod, endpoint.method.uppercased() != filterMethod.uppercased() {
                return false
            }
            if let filterTag = filter.filterTag, !(endpoint.tags ?? []).contains(filterTag) { return false }
            return true
        }
        return filtered.map { endpoint in
            EndpointSummary(
                id: endpoint.id, method: endpoint.method, path: endpoint.path, alias: endpoint.alias,
                referenceName: endpoint.referenceName, variantCount: endpoint.variants.count,
                defaultVariant: endpoint.variants.first { $0.isDefault == true }?.name)
        }
    }

    public func getEndpoint(handle: String, id: String) async throws -> EndpointDocument {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject
        guard let endpoint = project.endpoints.first(where: { $0.id == id }) else {
            throw MoqServiceError.endpointNotFound(id)
        }
        return endpoint
    }

    // MARK: - Endpoint naming helper (stateless)

    public func suggestEndpointID(_ input: SuggestEndpointIDInput) -> SuggestedEndpointIdentity {
        // Mirrors MoqImport's ImportConverter.endpointID derivation, without pulling in a
        // MoqImport dependency for one function.
        var normalized = input.path
        if normalized.hasPrefix("/") { normalized.removeFirst() }
        normalized = normalized.replacingOccurrences(of: #"\{[^}]+\}"#, with: "param", options: .regularExpression)
        normalized = normalized.replacingOccurrences(of: "[^a-zA-Z0-9/]", with: "", options: .regularExpression)
        normalized = normalized.replacingOccurrences(of: "/", with: "-").lowercased()
        while normalized.hasSuffix("-") { normalized.removeLast() }
        let id = normalized.isEmpty ? "\(input.method.lowercased())-root" : "\(input.method.lowercased())-\(normalized)"

        let alias =
            input.alias?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? input.alias! : EndpointAlias.defaultAlias(method: input.method, path: input.path)
        let referenceName = suggestedEndpointReferenceName(preferredSource: alias, fallbackID: id)

        return SuggestedEndpointIdentity(id: id, alias: alias, referenceName: referenceName)
    }

    // MARK: - Endpoint mutation

    public func upsertEndpoint(
        handle: String, input: EndpointUpsertInput, autosave: Bool
    ) async throws -> EndpointDocument {
        let session = try await session(handle)
        let store = try await session.currentStore()

        if MoqFormatRules.isReservedPath(input.path) {
            throw ProjectValidationInputError.reservedPath(input.path)
        }

        let project = await store.currentProject
        let existingVariants = project.endpoints.first(where: { $0.id == input.id })?.variants ?? []
        let document = input.makeDocument(existingVariants: existingVariants)

        if project.endpoints.contains(where: { $0.id == input.id }) {
            try await store.updateEndpoint(id: input.id, document)
        } else {
            try await store.addEndpoint(document)
        }
        try await session.recordMutation(autosave: autosave)
        return document
    }

    public func removeEndpoint(handle: String, id: String, autosave: Bool) async throws {
        let session = try await session(handle)
        let store = try await session.currentStore()
        try await store.removeEndpoint(id: id)
        try await session.recordMutation(autosave: autosave)
    }

    // MARK: - Variant mutation

    public func upsertVariant(
        handle: String, endpointID: String, variant: ProjectVariant, autosave: Bool
    ) async throws {
        let session = try await session(handle)
        let store = try await session.currentStore()
        try await store.upsertVariant(endpointID: endpointID, variant)
        try await session.recordMutation(autosave: autosave)
    }

    public func removeVariant(handle: String, input: RemoveVariantInput, autosave: Bool) async throws {
        let session = try await session(handle)
        let store = try await session.currentStore()
        try await store.removeVariant(endpointID: input.endpointId, name: input.name)
        try await session.recordMutation(autosave: autosave)
    }

    // MARK: - Validation / save

    /// Validates the project currently open in `handle`.
    public func validateProject(handle: String) async throws -> ValidationResult {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject
        return ValidationResult(diagnostics: ProjectValidator().validate(project))
    }

    /// Validates a project value directly, with no session and nothing on disk required beyond
    /// what `projectPath` points at for fixture-existence checks. This is the surface a caller
    /// editing an in-memory, unsaved project (Studio) actually needs — everything else on this
    /// type assumes a project that was opened through a session first.
    public func validateProject(_ project: MoqProject) -> ValidationResult {
        ValidationResult(diagnostics: ProjectValidator().validate(project))
    }

    public func saveProject(handle: String) async throws {
        let session = try await session(handle)
        try await session.save()
    }

    /// Writes a whole edited `MoqProject` value to disk in one call: opens the bundle at
    /// `project.projectPath` if one exists there (preserving crash-safe staged replacement and
    /// the on-disk-changed guard `ProjectStore.save` already provides), or creates one if it
    /// doesn't, replaces the in-memory manifest/endpoints, and saves.
    ///
    /// This is the whole-project counterpart to the incremental `upsertEndpoint`/`upsertVariant`
    /// calls: a client that edits a `MoqProject` value in memory (Studio) and wants the complete
    /// result persisted, rather than one endpoint or variant mutation at a time (an MCP session).
    /// Both end up going through the same `ProjectStore.save()` — this is not a second writer,
    /// just a different entry point into the one writer that exists.
    public func writeProject(handle: String, project: MoqProject, force: Bool) async throws -> ProjectDescription {
        let session = try await session(handle)
        let targetPath = (project.projectPath as NSString).standardizingPath

        // Re-opening on every write would re-read whatever is currently on disk and adopt *its*
        // fingerprint as the new baseline — silently defeating ProjectStore.save's own
        // on-disk-changed guard, since the "concurrent change" would already be folded in before
        // the check ever runs. Reuse the session's existing store (and the fingerprint it
        // captured back when it was first opened/created) whenever it's already the right
        // project; only open-or-create fresh when there's no live store yet, or the caller has
        // retargeted to a different path (Save As).
        var alreadyOpenAtTarget = false
        if await session.isOpen, let existingStore = try? await session.currentStore() {
            let currentPath = await existingStore.currentProject.projectPath
            alreadyOpenAtTarget = currentPath == targetPath
        }

        if !alreadyOpenAtTarget {
            let manifestPath = (targetPath as NSString).appendingPathComponent("project.yml")
            if FileManager.default.fileExists(atPath: manifestPath) {
                try await session.open(path: targetPath, force: force)
            } else {
                try await session.create(manifest: project.manifest, at: targetPath, force: force)
            }
        }

        let store = try await session.currentStore()
        await store.replace(manifest: project.manifest, endpoints: project.endpoints)
        try await session.save()
        return try await describeProject(handle: handle)
    }

    // MARK: - Import

    /// Merges `spec` into the session's open project and reconciles the result back into `store`
    /// endpoint-by-endpoint (`merge` never removes endpoints, so this is always add-or-update,
    /// never remove).
    private func applyImport(
        _ spec: ParsedSpec, common: ImportInputCommon, store: ProjectStore
    ) async throws
        -> ImportSummary
    {
        let existing = await store.currentProject
        let existingIDs = Set(existing.endpoints.map(\.id))
        let selection = ImportSelection(acceptedPaths: common.acceptPaths.map(Set.init))
        let policy = ImportMergePolicy(
            updateDetails: common.updateDetails ?? true, replaceExistingBodies: common.replaceExistingBodies ?? false)
        let merged = ImportConverter.merge(spec, selection: selection, policy: policy, into: existing)

        for endpoint in merged.endpoints {
            if existingIDs.contains(endpoint.id) {
                try await store.updateEndpoint(id: endpoint.id, endpoint)
            } else {
                try await store.addEndpoint(endpoint)
            }
        }

        return ImportSummary(
            projectEndpointCount: merged.endpoints.count,
            newEndpointCount: merged.endpoints.count - existing.endpoints.count,
            warnings: spec.warnings)
    }

    public func importHAR(
        handle: String, input: ImportHARInput, common: ImportInputCommon, autosave: Bool
    ) async throws -> ImportSummary {
        let session = try await session(handle)
        let store = try await session.currentStore()

        guard let content = try? String(contentsOfFile: input.path, encoding: .utf8) else {
            throw MoqServiceError.importReadFailed(input.path)
        }
        let spec: ParsedSpec
        do {
            spec = try HARImporter.parse(content)
        } catch {
            throw MoqServiceError.importParseFailed("\(error)")
        }

        let summary = try await applyImport(spec, common: common, store: store)
        try await session.recordMutation(autosave: autosave)
        return summary
    }

    public func importOpenAPI(
        handle: String, input: ImportOpenAPIInput, common: ImportInputCommon, autosave: Bool
    ) async throws -> ImportSummary {
        let session = try await session(handle)
        let store = try await session.currentStore()

        let content: String
        if input.source.hasPrefix("http://") || input.source.hasPrefix("https://") {
            guard allowNetworkImport else {
                throw MoqServiceError.networkImportDisabled
            }
            do {
                let fetched = try await SpecFetcher.fetchSpec(from: input.source, auth: input.auth?.resolved)
                content = fetched.content
            } catch {
                throw MoqServiceError.importFetchFailed("\(error)")
            }
        } else {
            guard let fileContent = try? String(contentsOfFile: input.source, encoding: .utf8) else {
                throw MoqServiceError.importReadFailed(input.source)
            }
            content = fileContent
        }

        let spec: ParsedSpec
        do {
            spec = try OpenAPIImporter.parse(content)
        } catch {
            throw MoqServiceError.importParseFailed("\(error)")
        }

        let summary = try await applyImport(spec, common: common, store: store)
        try await session.recordMutation(autosave: autosave)
        return summary
    }
}

/// Input-validation failures that aren't `ProjectStoreError` (the store never sees the input).
public enum ProjectValidationInputError: Error, CustomStringConvertible, Sendable {
    case reservedPath(String)

    public var description: String {
        switch self {
        case .reservedPath(let path):
            return "Path \"\(path)\" is reserved and cannot be used by mock endpoints."
        }
    }
}
