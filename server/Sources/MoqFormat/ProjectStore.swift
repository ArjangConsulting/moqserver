import Crypto
import Foundation
import Logging
import MoqCore

private let logger = Logger(label: "moqserver.format.ProjectStore")

/// Owns all mutating disk operations for a `.moqproj` bundle: creation, in-memory editing of
/// endpoints and variants, fixture management, and crash-recoverable persistence.
///
/// Actor-isolated to serialize mutations from a single process (matching
/// `InMemoryMockStore`'s concurrency model). It does **not** by itself protect against a second
/// process (Studio, another MCP session, a manual edit) touching the same bundle concurrently —
/// see the bundle fingerprint check in `save()` for that.
///
/// `init` and `ProjectStore.create` perform only *structural* loading: a project with zero
/// endpoints, or an endpoint with zero variants, loads and saves successfully. Semantic
/// completeness (required for `serve`/`validate`) is checked separately via `ProjectValidator`.
public actor ProjectStore {
    private var project: MoqProject
    /// The fingerprint of the on-disk bundle as of the last load or save, used to detect
    /// concurrent external changes. `nil` only when the destination did not yet exist.
    private var fingerprint: Data?

    /// Opens an existing `.moqproj` bundle, first recovering any interrupted transaction left
    /// behind by a previous crash (see `recoverIfNeeded`).
    public init(path: String) throws {
        let recovered = try Self.recoverIfNeeded(at: path)
        self.project = try ProjectLoader().load(from: recovered)
        self.fingerprint = try? Self.computeFingerprint(at: recovered)
    }

    /// Creates a new `.moqproj` bundle with an empty `endpoints/` directory and opens it.
    ///
    /// The manifest and directory structure are written immediately (not staged), so a crash
    /// right after creation still leaves a reopenable, if empty, project.
    public static func create(manifest: ProjectManifest, at path: String) throws -> ProjectStore {
        let destination = (path as NSString).standardizingPath
        let manifestPath = (destination as NSString).appendingPathComponent("project.yml")
        if FileManager.default.fileExists(atPath: manifestPath) {
            throw ProjectStoreError.projectAlreadyExists(destination)
        }
        let empty = MoqProject(manifest: manifest, endpoints: [], projectPath: destination)
        try ProjectWriter().write(empty, to: destination)
        return try ProjectStore(path: destination)
    }

    /// The current in-memory project. May differ from what's on disk until `save()` is called.
    public var currentProject: MoqProject { project }

    // MARK: - Project-level operations

    /// Persists a new location for this project: saves the current in-memory state to
    /// `newPath`, then removes the old bundle directory if it existed and differs from the new
    /// one. Note this is a persist-and-relocate, not a literal directory move — every save
    /// regenerates the bundle from the in-memory model via `ProjectWriter`, so "rename" and
    /// "save to a new path" are the same operation here.
    public func rename(to newPath: String) throws {
        let oldPath = project.projectPath
        let newDestination = (newPath as NSString).standardizingPath
        guard newDestination != oldPath else {
            try save()
            return
        }
        project = MoqProject(manifest: project.manifest, endpoints: project.endpoints, projectPath: newDestination)
        fingerprint = nil
        do {
            // Existing bodyFile fixtures still live under oldPath — the in-memory project's
            // projectPath was just reassigned to newDestination above, so save()'s own default
            // (copy fixtures forward from its own projectPath) would look for them in a
            // directory that doesn't have them yet.
            try save(sourceRoot: oldPath)
        } catch {
            // Roll back the in-memory path change so the store still reflects reality.
            project = MoqProject(manifest: project.manifest, endpoints: project.endpoints, projectPath: oldPath)
            throw error
        }
        if FileManager.default.fileExists(atPath: oldPath) {
            try? FileManager.default.removeItem(atPath: oldPath)
        }
    }

    /// Deletes the on-disk bundle directory. The store instance should be discarded afterward;
    /// further mutation and `save()` will recreate a bundle at the same path from whatever
    /// remains in memory, which is rarely what's wanted after an explicit delete.
    public func delete() throws {
        let path = project.projectPath
        guard FileManager.default.fileExists(atPath: path) else { return }
        try FileManager.default.removeItem(atPath: path)
        fingerprint = nil
    }

    /// Stages the in-memory project as a complete new bundle, verifies it reloads structurally,
    /// then atomically replaces the on-disk bundle. See the type documentation for the recovery
    /// contract this establishes.
    ///
    /// - Parameter sourceRoot: where a `bodyFile` variant's *existing* fixture is actually
    ///   copied forward from. Defaults to this store's own `projectPath` — correct for the
    ///   ordinary "load, edit, save in place" case, where a fixture referenced by `bodyFile`
    ///   already lives under this same directory. Pass the store's *old* path explicitly when
    ///   saving somewhere else for the first time (`rename(to:)` does this) — otherwise a fresh
    ///   destination with no fixtures yet is searched for a fixture that was never copied there,
    ///   and `save()` throws `fixtureNotFound` for every pre-existing `bodyFile` reference.
    ///
    /// Throws `ProjectStoreError.projectChangedOnDisk` if the bundle was modified by another
    /// process since this store last loaded or saved it — callers must reopen (`init(path:)`)
    /// or explicitly retarget (`rename(to:)`) rather than silently overwrite.
    public func save(sourceRoot: String? = nil) throws {
        let destination = project.projectPath
        let parent = (destination as NSString).deletingLastPathComponent
        let base = (destination as NSString).lastPathComponent
        let transactionID = UUID().uuidString
        let stagingDir = (parent as NSString).appendingPathComponent(".\(base).staging-\(transactionID)")
        let backupDir = (parent as NSString).appendingPathComponent(".\(base).backup-\(transactionID)")

        var stagingCreated = false
        do {
            let staged = try materializeAndStage(
                project, into: stagingDir, sourceRoot: sourceRoot ?? project.projectPath)
            stagingCreated = true

            // The staged bundle must be structurally reloadable before we touch the destination.
            _ = try ProjectLoader().load(from: stagingDir)

            let destinationExists = FileManager.default.fileExists(atPath: destination)
            if destinationExists {
                let onDisk = try Self.computeFingerprint(at: destination)
                if let expected = fingerprint, onDisk != expected {
                    throw ProjectStoreError.projectChangedOnDisk
                }
            }

            var backupCreated = false
            if destinationExists {
                try FileManager.default.moveItem(atPath: destination, toPath: backupDir)
                backupCreated = true
            } else {
                let parentExists = FileManager.default.fileExists(atPath: parent)
                if !parentExists {
                    try FileManager.default.createDirectory(atPath: parent, withIntermediateDirectories: true)
                }
            }

            do {
                try FileManager.default.moveItem(atPath: stagingDir, toPath: destination)
            } catch {
                if backupCreated {
                    try? FileManager.default.moveItem(atPath: backupDir, toPath: destination)
                }
                throw error
            }
            stagingCreated = false

            if backupCreated {
                try? FileManager.default.removeItem(atPath: backupDir)
            }

            let persisted = MoqProject(
                manifest: staged.manifest, endpoints: staged.endpoints, projectPath: destination)
            self.project = persisted
            self.fingerprint = try Self.computeFingerprint(at: destination)
            logger.info("Project saved: \(persisted.manifest.name) with \(persisted.endpoints.count) endpoint(s)")
        } catch {
            if stagingCreated {
                try? FileManager.default.removeItem(atPath: stagingDir)
            }
            throw error
        }
    }

    /// Replaces the entire in-memory manifest and endpoint set in one step, keeping
    /// `projectPath` (and therefore where `save()` looks for pre-existing fixtures to copy
    /// forward) unchanged. For a caller that edits a whole `MoqProject` value in memory and wants
    /// to hand the complete result over for writing — a client-side edit session (Studio) via
    /// `moq-format`, rather than the incremental `addEndpoint`/`updateEndpoint` calls an
    /// agent-driven MCP session makes one at a time.
    public func replace(manifest: ProjectManifest, endpoints: [EndpointDocument]) {
        project = MoqProject(manifest: manifest, endpoints: endpoints, projectPath: project.projectPath)
    }

    // MARK: - Endpoint operations

    public func addEndpoint(_ doc: EndpointDocument) throws {
        guard MoqFormatRules.isValidEndpointID(doc.id) else {
            throw ProjectStoreError.invalidEndpointID(doc.id)
        }
        guard !project.endpoints.contains(where: { $0.id == doc.id }) else {
            throw ProjectStoreError.endpointAlreadyExists(doc.id)
        }
        project = MoqProject(
            manifest: project.manifest, endpoints: project.endpoints + [doc], projectPath: project.projectPath)
    }

    public func updateEndpoint(id: String, _ doc: EndpointDocument) throws {
        guard doc.id == id else {
            throw ProjectStoreError.endpointIDMismatch(expected: id, actual: doc.id)
        }
        guard let index = project.endpoints.firstIndex(where: { $0.id == id }) else {
            throw ProjectStoreError.endpointNotFound(id)
        }
        var endpoints = project.endpoints
        endpoints[index] = doc
        project = MoqProject(manifest: project.manifest, endpoints: endpoints, projectPath: project.projectPath)
    }

    /// Changes only an endpoint's id — alias, reference_name, and all other fields are carried
    /// over unchanged. At save time the old `endpoints/<oldID>.yml` file simply isn't
    /// regenerated (every save rebuilds the endpoint file list from scratch), so this needs no
    /// explicit file-rename step.
    public func renameEndpoint(id: String, to newID: String) throws {
        guard MoqFormatRules.isValidEndpointID(newID) else {
            throw ProjectStoreError.invalidEndpointID(newID)
        }
        guard let index = project.endpoints.firstIndex(where: { $0.id == id }) else {
            throw ProjectStoreError.endpointNotFound(id)
        }
        guard id == newID || !project.endpoints.contains(where: { $0.id == newID }) else {
            throw ProjectStoreError.endpointAlreadyExists(newID)
        }
        let old = project.endpoints[index]
        let renamed = EndpointDocument(
            id: newID,
            alias: old.alias,
            description: old.description,
            referenceName: old.referenceName,
            method: old.method,
            path: old.path,
            tags: old.tags,
            auth: old.auth,
            requestRules: old.requestRules,
            operation: old.operation,
            network: old.network,
            variants: old.variants,
            strictCallCount: old.strictCallCount
        )
        var endpoints = project.endpoints
        endpoints[index] = renamed
        project = MoqProject(manifest: project.manifest, endpoints: endpoints, projectPath: project.projectPath)
    }

    public func removeEndpoint(id: String) throws {
        guard let index = project.endpoints.firstIndex(where: { $0.id == id }) else {
            throw ProjectStoreError.endpointNotFound(id)
        }
        var endpoints = project.endpoints
        endpoints.remove(at: index)
        project = MoqProject(manifest: project.manifest, endpoints: endpoints, projectPath: project.projectPath)
    }

    // MARK: - Variant operations

    /// Inserts or replaces (by name) a variant on an endpoint. If `variant.isDefault == true`,
    /// clears the default flag on every other variant first, so "at most one default variant"
    /// can never be violated by construction.
    ///
    /// Returns whether an existing variant was replaced. Variant identity is case-insensitive, so
    /// `upsert("Success")` followed by `upsert("success")` replaces rather than adds a second
    /// variant — the returned `.replaced(previousName:)` lets a caller notice that and skip a
    /// follow-up `removeVariant` that would otherwise destroy the just-written variant.
    @discardableResult
    public func upsertVariant(endpointID: String, _ variant: ProjectVariant) throws -> VariantUpsertOutcome {
        guard let endpointIndex = project.endpoints.firstIndex(where: { $0.id == endpointID }) else {
            throw ProjectStoreError.endpointNotFound(endpointID)
        }
        let endpoint = project.endpoints[endpointIndex]
        var variants = endpoint.variants

        if variant.isDefault == true {
            variants = variants.map { existing in
                guard existing.name.caseInsensitiveCompare(variant.name) != .orderedSame,
                    existing.isDefault == true
                else {
                    return existing
                }
                return ProjectVariant(
                    name: existing.name,
                    referenceName: existing.referenceName,
                    isDefault: false,
                    status: existing.status,
                    headers: existing.headers,
                    requestMatch: existing.requestMatch,
                    body: existing.body,
                    bodyFile: existing.bodyFile,
                    delayMs: existing.delayMs,
                    callCount: existing.callCount
                )
            }
        }

        let outcome: VariantUpsertOutcome
        if let variantIndex = variants.firstIndex(where: {
            $0.name.caseInsensitiveCompare(variant.name) == .orderedSame
        }) {
            outcome = .replaced(previousName: variants[variantIndex].name)
            variants[variantIndex] = variant
        } else {
            outcome = .created
            variants.append(variant)
        }

        let updatedEndpoint = endpoint.withVariants(variants)
        var endpoints = project.endpoints
        endpoints[endpointIndex] = updatedEndpoint
        project = MoqProject(manifest: project.manifest, endpoints: endpoints, projectPath: project.projectPath)
        return outcome
    }

    /// Removes a variant by name (case-insensitive) and returns the *stored* name of the variant
    /// that was actually removed — which can differ in casing from `name`.
    @discardableResult
    public func removeVariant(endpointID: String, name: String) throws -> String {
        guard let endpointIndex = project.endpoints.firstIndex(where: { $0.id == endpointID }) else {
            throw ProjectStoreError.endpointNotFound(endpointID)
        }
        let endpoint = project.endpoints[endpointIndex]
        guard
            let variantIndex = endpoint.variants.firstIndex(where: {
                $0.name.caseInsensitiveCompare(name) == .orderedSame
            })
        else {
            throw ProjectStoreError.variantNotFound(endpointID: endpointID, variantName: name)
        }
        let removedName = endpoint.variants[variantIndex].name
        var variants = endpoint.variants
        variants.remove(at: variantIndex)
        var endpoints = project.endpoints
        endpoints[endpointIndex] = endpoint.withVariants(variants)
        project = MoqProject(manifest: project.manifest, endpoints: endpoints, projectPath: project.projectPath)
        return removedName
    }

    // MARK: - Fixture operations (advanced — bypass the normal inline-body flow)

    /// Writes exact bytes to a fixture path under the *currently persisted* bundle, immediately
    /// (not staged as part of a `save()` transaction). Prefer setting `ProjectVariant.body` and
    /// letting `save()` materialize it — this exists for callers that already have externally
    /// prepared fixture bytes to place at a specific path.
    public func writeFixture(_ data: Data, relativePath: String) throws {
        guard let url = FixturePathResolver.resolve(bodyFile: relativePath, projectPath: project.projectPath) else {
            throw ProjectStoreError.invalidFixturePath(relativePath)
        }
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    public func readFixture(_ relativePath: String) throws -> Data {
        guard let url = FixturePathResolver.resolve(bodyFile: relativePath, projectPath: project.projectPath) else {
            throw ProjectStoreError.invalidFixturePath(relativePath)
        }
        return try Data(contentsOf: url)
    }

    public func removeFixture(_ relativePath: String) throws {
        guard let url = FixturePathResolver.resolve(bodyFile: relativePath, projectPath: project.projectPath) else {
            throw ProjectStoreError.invalidFixturePath(relativePath)
        }
        try FileManager.default.removeItem(at: url)
    }

    /// Files under `fixtures/` not referenced by any variant's `body_file`. Symlinks are
    /// reported but never followed.
    public func collectOrphanedFixtures() -> [String] {
        let fm = FileManager.default
        let fixturesDir = (project.projectPath as NSString).appendingPathComponent("fixtures")
        guard let enumerator = fm.enumerator(atPath: fixturesDir) else { return [] }

        var referenced: Set<String> = []
        for endpoint in project.endpoints {
            for variant in endpoint.variants {
                if let bodyFile = variant.bodyFile { referenced.insert(bodyFile) }
            }
        }

        var orphans: [String] = []
        while let item = enumerator.nextObject() as? String {
            if enumerator.fileAttributes?[.type] as? FileAttributeType == .typeSymbolicLink {
                enumerator.skipDescendants()
                continue
            }
            let fullPath = (fixturesDir as NSString).appendingPathComponent(item)
            var isDirectory: ObjCBool = false
            guard fm.fileExists(atPath: fullPath, isDirectory: &isDirectory), !isDirectory.boolValue else {
                continue
            }
            let relativePath = "fixtures/\(item)"
            if !referenced.contains(relativePath) {
                orphans.append(relativePath)
            }
        }
        return orphans.sorted()
    }

    // MARK: - Save-time fixture materialization

    /// Transforms inline `body` values into `body_file` references pointing at newly written
    /// fixture bytes, writes the resulting bundle via `ProjectWriter`, and copies forward every
    /// fixture that was already referenced by an explicit (caller-set) `body_file` so it survives
    /// the save. Returns the persisted project (with `projectPath` set to `stagingDir`).
    private func materializeAndStage(
        _ project: MoqProject, into stagingDir: String, sourceRoot: String
    ) throws
        -> MoqProject
    {
        var usedFixturePaths: Set<String> = []
        for endpoint in project.endpoints {
            for variant in endpoint.variants {
                if let bodyFile = variant.bodyFile { usedFixturePaths.insert(bodyFile) }
            }
        }

        var pendingWrites: [(relativePath: String, data: Data)] = []
        var referencedExistingFixtures: Set<String> = []

        let newEndpoints = try project.endpoints.map { endpoint -> EndpointDocument in
            let newVariants = try endpoint.variants.map { variant -> ProjectVariant in
                if let bodyFile = variant.bodyFile {
                    referencedExistingFixtures.insert(bodyFile)
                    return variant
                }
                guard let body = variant.body else { return variant }

                let contentType = variant.headers?.first {
                    $0.key.caseInsensitiveCompare("Content-Type") == .orderedSame
                }?.value
                let (data, ext) = try Self.fixtureBytes(
                    for: body, encoding: variant.bodyEncoding, contentType: contentType)
                let base =
                    "fixtures/responses/\(endpoint.id)/\(Self.sanitizeFixtureSegment(endpoint.path))-\(Self.sanitizeFixtureSegment(variant.name))"
                var candidate = "\(base).\(ext)"
                var suffix = 2
                while usedFixturePaths.contains(candidate) {
                    candidate = "\(base)-\(suffix).\(ext)"
                    suffix += 1
                }
                usedFixturePaths.insert(candidate)
                pendingWrites.append((candidate, data))

                return ProjectVariant(
                    name: variant.name,
                    referenceName: variant.referenceName,
                    isDefault: variant.isDefault,
                    status: variant.status,
                    headers: variant.headers,
                    requestMatch: variant.requestMatch,
                    body: nil,
                    bodyEncoding: nil,
                    bodyFile: candidate,
                    delayMs: variant.delayMs,
                    callCount: variant.callCount
                )
            }
            return endpoint.withVariants(newVariants)
        }

        let persistedProject = MoqProject(
            manifest: project.manifest, endpoints: newEndpoints, projectPath: stagingDir)
        try ProjectWriter().write(persistedProject, to: stagingDir)

        for (relativePath, data) in pendingWrites {
            let url = URL(fileURLWithPath: stagingDir).appendingPathComponent(relativePath)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url)
        }

        for relativePath in referencedExistingFixtures {
            let destinationURL = URL(fileURLWithPath: stagingDir).appendingPathComponent(relativePath)
            guard
                let sourceURL = FixturePathResolver.resolve(
                    bodyFile: relativePath, projectPath: sourceRoot)
            else {
                throw ProjectStoreError.invalidFixturePath(relativePath)
            }
            guard FileManager.default.fileExists(atPath: sourceURL.path) else {
                throw ProjectStoreError.fixtureNotFound(relativePath)
            }
            try FileManager.default.createDirectory(
                at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Self.copyFileNotFollowingSymlinks(from: sourceURL, to: destinationURL)
        }

        return persistedProject
    }

    /// Shared with runtime conversion in `ProjectToRuntimeConverter` — see `InlineBody`.
    /// Fixtures are pretty-printed so they diff well; responses on the wire are compact.
    private static func fixtureBytes(
        for body: AnyCodableValue,
        encoding: BodyEncoding?,
        contentType: String?
    ) throws -> (data: Data, ext: String) {
        let resolved = try InlineBody.resolve(
            body, encoding: encoding, contentType: contentType, prettyPrintStructured: true)
        return (resolved.data, resolved.fileExtension)
    }

    private static func sanitizeFixtureSegment(_ value: String) -> String {
        var result = ""
        var lastWasSeparator = false
        for scalar in value.lowercased().unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                result.unicodeScalars.append(scalar)
                lastWasSeparator = false
            } else if !lastWasSeparator {
                result.append("-")
                lastWasSeparator = true
            }
        }
        let trimmed = result.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return trimmed.isEmpty ? "value" : trimmed
    }

    private static func copyFileNotFollowingSymlinks(from source: URL, to destination: URL) throws {
        let resourceValues = try source.resourceValues(forKeys: [.isSymbolicLinkKey])
        guard resourceValues.isSymbolicLink != true else {
            throw ProjectStoreError.invalidFixturePath(source.path)
        }
        try FileManager.default.copyItem(at: source, to: destination)
    }

    // MARK: - Transaction recovery

    /// Recovers remnants of a `save()` interrupted by a crash, then returns the (possibly
    /// unchanged) destination path to load from.
    ///
    /// - If the destination exists, it's valid; any sibling staging/backup directories are
    ///   leftovers from a completed or abandoned transaction and are removed.
    /// - If the destination is missing and exactly one sibling backup directory exists, that
    ///   backup is restored to the destination (the crash happened after the old bundle was
    ///   moved aside but before the new one was moved in).
    /// - If the destination is missing and more than one sibling backup exists, recovery is
    ///   ambiguous and `ProjectStoreError.projectRecoveryRequired` is thrown with their paths.
    private static func recoverIfNeeded(at path: String) throws -> String {
        let destination = (path as NSString).standardizingPath
        let parent = (destination as NSString).deletingLastPathComponent
        let base = (destination as NSString).lastPathComponent
        let fm = FileManager.default

        let siblings = (try? fm.contentsOfDirectory(atPath: parent)) ?? []
        let stagingPrefix = ".\(base).staging-"
        let backupPrefix = ".\(base).backup-"
        let stagingDirs = siblings.filter { $0.hasPrefix(stagingPrefix) }
            .map { (parent as NSString).appendingPathComponent($0) }
        let backupDirs = siblings.filter { $0.hasPrefix(backupPrefix) }
            .map { (parent as NSString).appendingPathComponent($0) }

        if fm.fileExists(atPath: destination) {
            for dir in stagingDirs { try? fm.removeItem(atPath: dir) }
            for dir in backupDirs { try? fm.removeItem(atPath: dir) }
            return destination
        }

        guard !backupDirs.isEmpty else {
            return destination
        }
        guard backupDirs.count == 1 else {
            throw ProjectStoreError.projectRecoveryRequired(backupDirs.sorted())
        }

        try fm.moveItem(atPath: backupDirs[0], toPath: destination)
        for dir in stagingDirs { try? fm.removeItem(atPath: dir) }
        return destination
    }

    // MARK: - Fingerprinting

    /// SHA-256 over the sorted relative paths and bytes of `project.yml`, every endpoint YAML
    /// file, and every fixture referenced by a `body_file`. Unreferenced fixtures and
    /// staging/backup directories are excluded — this fingerprints the *loaded project*, not
    /// arbitrary bytes on disk.
    private static func computeFingerprint(at path: String) throws -> Data {
        let project = try ProjectLoader().load(from: path)
        var hasher = SHA256()

        try hashFile(
            (path as NSString).appendingPathComponent("project.yml"), relativePath: "project.yml", into: &hasher)

        for endpoint in project.endpoints.sorted(by: { $0.id < $1.id }) {
            let relativePath = "endpoints/\(endpoint.id).yml"
            try hashFile(
                (path as NSString).appendingPathComponent(relativePath), relativePath: relativePath, into: &hasher)
        }

        var referencedFixtures: Set<String> = []
        for endpoint in project.endpoints {
            for variant in endpoint.variants {
                if let bodyFile = variant.bodyFile { referencedFixtures.insert(bodyFile) }
            }
        }
        for relativePath in referencedFixtures.sorted() {
            guard let url = FixturePathResolver.resolve(bodyFile: relativePath, projectPath: path) else { continue }
            try hashFile(url.path, relativePath: relativePath, into: &hasher)
        }

        return Data(hasher.finalize())
    }

    private static func hashFile(_ filePath: String, relativePath: String, into hasher: inout SHA256) throws {
        hasher.update(data: Data(relativePath.utf8))
        hasher.update(data: try Data(contentsOf: URL(fileURLWithPath: filePath)))
    }
}

/// Result of `ProjectStore.upsertVariant` — distinguishes a fresh insertion from an in-place
/// replacement so a caller can surface the replacement (variant names match case-insensitively)
/// instead of silently issuing a destructive `removeVariant` afterward.
public enum VariantUpsertOutcome: Equatable, Sendable {
    /// No existing variant matched by name; the variant was appended.
    case created
    /// An existing variant was replaced in place. `previousName` is its stored name before the
    /// replacement, which differs from the new name when the match was only case-insensitive.
    case replaced(previousName: String)
}

extension EndpointDocument {
    /// Returns a copy with `variants` replaced; every other field is carried over unchanged.
    fileprivate func withVariants(_ variants: [ProjectVariant]) -> EndpointDocument {
        EndpointDocument(
            id: id,
            alias: alias,
            description: description,
            referenceName: referenceName,
            method: method,
            path: path,
            tags: tags,
            auth: auth,
            requestRules: requestRules,
            operation: operation,
            network: network,
            variants: variants,
            strictCallCount: strictCallCount
        )
    }
}

/// Errors specific to `ProjectStore` mutation and persistence, distinct from the structural
/// `ProjectLoadError` and semantic `ValidationDiagnostic`.
public enum ProjectStoreError: Error, CustomStringConvertible, Equatable, Sendable {
    case projectAlreadyExists(String)
    case endpointNotFound(String)
    case endpointAlreadyExists(String)
    case endpointIDMismatch(expected: String, actual: String)
    case variantNotFound(endpointID: String, variantName: String)
    case invalidEndpointID(String)
    case invalidFixturePath(String)
    case fixtureNotFound(String)
    case projectChangedOnDisk
    case projectRecoveryRequired([String])

    public var description: String {
        switch self {
        case .projectAlreadyExists(let path):
            return "A project already exists at: \(path)"
        case .endpointNotFound(let id):
            return "No endpoint with id: \(id)"
        case .endpointAlreadyExists(let id):
            return "An endpoint with id \"\(id)\" already exists"
        case .endpointIDMismatch(let expected, let actual):
            return "Endpoint id mismatch: expected \"\(expected)\", got \"\(actual)\""
        case .variantNotFound(let endpointID, let variantName):
            return "No variant named \"\(variantName)\" on endpoint \"\(endpointID)\""
        case .invalidEndpointID(let id):
            return "Invalid endpoint id: \"\(id)\" (must match ^[a-z0-9][a-z0-9-]*$)"
        case .invalidFixturePath(let path):
            return "Invalid fixture path (must resolve inside fixtures/): \(path)"
        case .fixtureNotFound(let path):
            return "Fixture not found: \(path)"
        case .projectChangedOnDisk:
            return "The project on disk changed since it was last loaded or saved. Reopen before saving again."
        case .projectRecoveryRequired(let backups):
            return
                "Multiple interrupted transactions found; manual recovery required: \(backups.joined(separator: ", "))"
        }
    }
}
