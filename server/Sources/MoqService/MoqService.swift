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
            endpointCount: project.endpoints.count, dirty: dirty, revision: await store.revision)
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

    /// Returns whether an existing variant was replaced (see `VariantUpsertOutcome`) so a caller
    /// can distinguish "added a variant" from "redefined one that already existed" — the latter
    /// matters because variant names match case-insensitively.
    @discardableResult
    public func upsertVariant(
        handle: String, endpointID: String, variant: ProjectVariant, autosave: Bool
    ) async throws -> VariantUpsertOutcome {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let outcome = try await store.upsertVariant(endpointID: endpointID, variant)
        try await session.recordMutation(autosave: autosave)
        return outcome
    }

    /// Returns the *stored* name of the variant actually removed, which can differ in casing from
    /// `input.name`.
    @discardableResult
    public func removeVariant(handle: String, input: RemoveVariantInput, autosave: Bool) async throws -> String {
        let session = try await session(handle)
        let store = try await session.currentStore()
        let removedName = try await store.removeVariant(endpointID: input.endpointId, name: input.name)
        try await session.recordMutation(autosave: autosave)
        return removedName
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
    public func writeProject(
        handle: String, project: MoqProject, force: Bool, expectedRevision: String? = nil
    ) async throws -> ProjectDescription {
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
        var priorPath: String?
        if await session.isOpen, let existingStore = try? await session.currentStore() {
            priorPath = await existingStore.currentProject.projectPath
            alreadyOpenAtTarget = priorPath == targetPath
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
        if let expectedRevision, await store.revision != expectedRevision {
            throw ProjectStoreError.projectChangedOnDisk
        }
        await store.replace(manifest: project.manifest, endpoints: project.endpoints)
        // Save As (retargeting to a path this session wasn't already open at): any bodyFile the
        // caller's endpoints reference still points at whatever this session's store was
        // previously open at — priorPath — not the freshly opened/created targetPath, which may
        // not have those fixtures yet. See ProjectStore.save's sourceRoot doc for what goes wrong
        // without this (fixtureNotFound on every pre-existing bodyFile reference).
        try await session.save(sourceRoot: alreadyOpenAtTarget ? nil : priorPath)
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

        let resolved = try await resolveOpenAPISource(input.source, auth: input.auth?.resolved)
        let spec: ParsedSpec
        do {
            spec = try OpenAPIImporter.parse(resolved.content)
        } catch {
            throw MoqServiceError.importParseFailed("\(error)")
        }

        let summary = try await applyImport(spec, common: common, store: store)
        try await session.recordMutation(autosave: autosave)
        return summary
    }

    // MARK: - Parse only

    /// Parses a HAR file into a [ParsedSpec] without merging it into any project or touching
    /// disk beyond reading `path`. The counterpart to [importHAR] for a caller (Studio) that
    /// wants to hold the parsed result for interactive review — accept/reject entries, generate
    /// AI content, rename responses — before anything is committed, rather than merge-and-save
    /// in one call the way an agent-driven MCP session does.
    public func parseHAR(path: String) throws -> ParsedSpec {
        guard let content = try? String(contentsOfFile: path, encoding: .utf8) else {
            throw MoqServiceError.importReadFailed(path)
        }
        do {
            return try HARImporter.parse(content)
        } catch {
            throw MoqServiceError.importParseFailed("\(error)")
        }
    }

    /// Parses an OpenAPI spec (from a file path or, if `allowNetworkImport` is set, an
    /// `http(s)://` URL) into a [ParsedSpec], same caveat as [parseHAR] — no merge, no save.
    /// Returns the resolved source alongside the spec (the URL actually fetched, following
    /// redirects, or the given path unchanged) so the caller can show the user what was actually
    /// imported.
    public func parseOpenAPI(source: String, auth: ImportAuthInput?) async throws -> ParsedOpenAPIResult {
        let resolved = try await resolveOpenAPISource(source, auth: auth?.resolved)
        do {
            let spec = try OpenAPIImporter.parse(resolved.content)
            return ParsedOpenAPIResult(spec: spec, resolvedSource: resolved.resolvedSource)
        } catch {
            throw MoqServiceError.importParseFailed("\(error)")
        }
    }

    /// Shared by [importOpenAPI] and [parseOpenAPI]: resolves `source` to spec content, fetching
    /// it if it's an `http(s)://` URL (subject to `allowNetworkImport` and `SpecFetcher`'s
    /// SSRF hardening) or reading it as a local file path otherwise.
    private func resolveOpenAPISource(
        _ source: String, auth: URLImportAuth?
    ) async throws -> (content: String, resolvedSource: String) {
        if source.hasPrefix("http://") || source.hasPrefix("https://") {
            guard allowNetworkImport else {
                throw MoqServiceError.networkImportDisabled
            }
            do {
                let fetched = try await SpecFetcher.fetchSpec(from: source, auth: auth)
                return (fetched.content, fetched.resolvedURL)
            } catch {
                throw MoqServiceError.importFetchFailed("\(error)")
            }
        }
        guard let fileContent = try? String(contentsOfFile: source, encoding: .utf8) else {
            throw MoqServiceError.importReadFailed(source)
        }
        return (fileContent, source)
    }
}

/// Result of a stateless OpenAPI parse: the parsed spec plus the source it actually came from
/// (a fetched URL may differ from the input after redirects; a file path is unchanged).
public struct ParsedOpenAPIResult: Codable, Sendable {
    public let spec: ParsedSpec
    public let resolvedSource: String

    enum CodingKeys: String, CodingKey {
        case spec
        case resolvedSource = "resolved_source"
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
