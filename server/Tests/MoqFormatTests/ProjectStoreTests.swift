import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

struct ProjectStoreTests {
    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("store-test-\(label)-\(UUID().uuidString).moqproj")
    }

    func manifest(name: String = "Store Test") -> ProjectManifest {
        ProjectManifest(
            version: "1",
            name: name,
            defaults: ProjectDefaults(
                delayMs: 0,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            )
        )
    }

    func endpoint(
        id: String = "get-users",
        method: String = "GET",
        path: String = "/users",
        variants: [ProjectVariant] = [ProjectVariant(name: "default", status: 200)]
    ) -> EndpointDocument {
        EndpointDocument(id: id, method: method, path: path, variants: variants)
    }

    @Test("opening a bundle cannot recover another writer's active staging directory")
    func recoveryRespectsLock() async throws {
        let path = tempPath("locked-recovery")
        _ = try ProjectStore.create(manifest: manifest(), at: path)
        defer { try? FileManager.default.removeItem(atPath: path) }
        let stage =
            (path as NSString).deletingLastPathComponent + "/." + (path as NSString).lastPathComponent + ".staging-live"
        try FileManager.default.createDirectory(atPath: stage, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: stage) }
        let lock = try BundleLock(path: path)
        defer { withExtendedLifetime(lock) {} }
        #expect(throws: ProjectStoreError.projectBusy) { _ = try ProjectStore(path: path) }
        #expect(FileManager.default.fileExists(atPath: stage))
    }

    @Test("a removed bundle is a conflict rather than permission to recreate stale data")
    func deletedProjectIsConflict() async throws {
        let path = tempPath("deleted-external")
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try FileManager.default.removeItem(atPath: path)
        await #expect(throws: ProjectStoreError.projectChangedOnDisk) { try await store.save() }
        #expect(!FileManager.default.fileExists(atPath: path))
    }

    @Test("changing the default and materializing fixtures preserve variant metadata")
    func savePreservesVariantMetadata() async throws {
        let path = tempPath("variant-metadata")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(variants: [
                ProjectVariant(
                    name: "binary", description: "A binary example", isDefault: true, status: 200,
                    body: .string("AAEC"), bodyEncoding: .base64)
            ]))
        try await store.upsertVariant(
            endpointID: "get-users", ProjectVariant(name: "other", isDefault: true, status: 200))
        try await store.save()
        let project = await store.currentProject
        let variant = try #require(project.endpoints.first?.variants.first)
        #expect(variant.description == "A binary example")
        #expect(variant.isDefault == false)
        let bodyFile = try #require(variant.bodyFile)
        #expect(try Data(contentsOf: URL(fileURLWithPath: path).appendingPathComponent(bodyFile)) == Data([0, 1, 2]))
    }

    // MARK: - Create / open

    @Test("create writes an empty structurally loadable bundle")
    func createWritesEmptyBundle() async throws {
        let path = tempPath("create")
        defer { try? FileManager.default.removeItem(atPath: path) }

        let store = try ProjectStore.create(manifest: manifest(), at: path)
        let project = await store.currentProject
        #expect(project.endpoints.isEmpty)

        // Reopening must succeed even though there are zero endpoints (structural, not semantic).
        let reopened = try ProjectStore(path: path)
        let reopenedProject = await reopened.currentProject
        #expect(reopenedProject.endpoints.isEmpty)
        #expect(reopenedProject.manifest.name == "Store Test")
    }

    @Test("create fails if a project already exists at the path")
    func createFailsWhenAlreadyExists() async throws {
        let path = tempPath("create-exists")
        defer { try? FileManager.default.removeItem(atPath: path) }
        _ = try ProjectStore.create(manifest: manifest(), at: path)

        #expect(throws: ProjectStoreError.self) {
            _ = try ProjectStore.create(manifest: manifest(), at: path)
        }
    }

    // MARK: - Endpoint CRUD

    @Test("addEndpoint and updateEndpoint round-trip through save/reload")
    func endpointCRUDRoundTrips() async throws {
        let path = tempPath("endpoint-crud")
        defer { try? FileManager.default.removeItem(atPath: path) }

        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()

        let reopened = try ProjectStore(path: path)
        let project = await reopened.currentProject
        #expect(project.endpoints.map(\.id) == ["get-users"])

        let updated = EndpointDocument(
            id: "get-users",
            description: "updated",
            method: "GET",
            path: "/users",
            variants: [ProjectVariant(name: "default", status: 200)]
        )
        try await reopened.updateEndpoint(id: "get-users", updated)
        try await reopened.save()

        let final = try ProjectStore(path: path)
        let finalProject = await final.currentProject
        #expect(finalProject.endpoints[0].description == "updated")
    }

    @Test("updateEndpoint rejects a mismatched id")
    func updateEndpointRejectsIDMismatch() async throws {
        let path = tempPath("update-mismatch")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())

        await #expect(throws: ProjectStoreError.self) {
            try await store.updateEndpoint(id: "get-users", endpoint(id: "different"))
        }
    }

    @Test("addEndpoint rejects a duplicate id")
    func addEndpointRejectsDuplicate() async throws {
        let path = tempPath("dup-endpoint")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())

        await #expect(throws: ProjectStoreError.self) {
            try await store.addEndpoint(endpoint())
        }
    }

    @Test("addEndpoint rejects an invalid id")
    func addEndpointRejectsInvalidID() async throws {
        let path = tempPath("invalid-endpoint")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        await #expect(throws: ProjectStoreError.self) {
            try await store.addEndpoint(endpoint(id: "Not_Valid"))
        }
    }

    @Test("removeEndpoint removes the endpoint and its persisted file")
    func removeEndpointPersists() async throws {
        let path = tempPath("remove-endpoint")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()

        try await store.removeEndpoint(id: "get-users")
        try await store.save()

        let endpointFile = (path as NSString).appendingPathComponent("endpoints/get-users.yml")
        #expect(!FileManager.default.fileExists(atPath: endpointFile))

        let reopened = try ProjectStore(path: path)
        let project = await reopened.currentProject
        #expect(project.endpoints.isEmpty)
    }

    @Test("removeEndpoint on an unknown id throws")
    func removeUnknownEndpointThrows() async throws {
        let path = tempPath("remove-unknown")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        await #expect(throws: ProjectStoreError.self) {
            try await store.removeEndpoint(id: "nope")
        }
    }

    @Test("renameEndpoint moves the id and the file on disk")
    func renameEndpointMovesFile() async throws {
        let path = tempPath("rename-endpoint")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint(id: "old-id"))
        try await store.save()

        try await store.renameEndpoint(id: "old-id", to: "new-id")
        try await store.save()

        let oldFile = (path as NSString).appendingPathComponent("endpoints/old-id.yml")
        let newFile = (path as NSString).appendingPathComponent("endpoints/new-id.yml")
        #expect(!FileManager.default.fileExists(atPath: oldFile))
        #expect(FileManager.default.fileExists(atPath: newFile))
    }

    @Test("renameEndpoint rejects colliding with an existing id")
    func renameEndpointRejectsCollision() async throws {
        let path = tempPath("rename-collision")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint(id: "a"))
        try await store.addEndpoint(endpoint(id: "b", path: "/b"))

        await #expect(throws: ProjectStoreError.self) {
            try await store.renameEndpoint(id: "a", to: "b")
        }
    }

    // MARK: - Variant CRUD

    @Test("upsertVariant clears default on siblings")
    func upsertVariantClearsSiblingDefault() async throws {
        let path = tempPath("variant-default")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(variants: [ProjectVariant(name: "a", isDefault: true, status: 200)]))

        try await store.upsertVariant(
            endpointID: "get-users", ProjectVariant(name: "b", isDefault: true, status: 201))

        let project = await store.currentProject
        let variants = project.endpoints[0].variants
        let defaults = variants.filter { $0.isDefault == true }
        #expect(defaults.count == 1)
        #expect(defaults.first?.name == "b")
    }

    @Test("upsertVariant replaces an existing variant by name")
    func upsertVariantReplacesByName() async throws {
        let path = tempPath("variant-replace")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint(variants: [ProjectVariant(name: "a", status: 200)]))

        try await store.upsertVariant(endpointID: "get-users", ProjectVariant(name: "a", status: 500))

        let project = await store.currentProject
        #expect(project.endpoints[0].variants.count == 1)
        #expect(project.endpoints[0].variants[0].status == 500)
    }

    @Test("upsertVariant reports created vs replaced, including a case-insensitive match")
    func upsertVariantReportsOutcome() async throws {
        let path = tempPath("variant-outcome")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint(variants: []))

        let first = try await store.upsertVariant(
            endpointID: "get-users", ProjectVariant(name: "Success", status: 200))
        #expect(first == .created)

        let second = try await store.upsertVariant(
            endpointID: "get-users", ProjectVariant(name: "success", status: 201))
        #expect(second == .replaced(previousName: "Success"))

        // The case-only rename replaced in place — there is still exactly one variant.
        let variants = await store.currentProject.endpoints[0].variants
        #expect(variants.count == 1)
        #expect(variants[0].name == "success")
        #expect(variants[0].status == 201)
    }

    @Test("removeVariant returns the stored name it removed, not the requested spelling")
    func removeVariantReturnsStoredName() async throws {
        let path = tempPath("variant-remove-name")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(variants: [ProjectVariant(name: "Success", status: 200)]))

        let removed = try await store.removeVariant(endpointID: "get-users", name: "success")
        #expect(removed == "Success")
        #expect(await store.currentProject.endpoints[0].variants.isEmpty)
    }

    @Test("removeVariant on an unknown endpoint throws")
    func removeVariantUnknownEndpointThrows() async throws {
        let path = tempPath("variant-unknown-endpoint")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        await #expect(throws: ProjectStoreError.self) {
            try await store.removeVariant(endpointID: "nope", name: "a")
        }
    }

    // MARK: - Fixture materialization

    @Test("save materializes an inline JSON body into a fixture file")
    func saveMaterializesJSONBody() async throws {
        let path = tempPath("materialize-json")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(
                variants: [
                    ProjectVariant(name: "ok", status: 200, body: .object(["hello": .string("world")]))
                ]))
        try await store.save()

        let project = await store.currentProject
        let variant = project.endpoints[0].variants[0]
        #expect(variant.body == nil)
        let bodyFile = try #require(variant.bodyFile)
        #expect(bodyFile.hasPrefix("fixtures/responses/get-users/"))
        #expect(bodyFile.hasSuffix(".json"))

        let bytes = try await store.readFixture(bodyFile)
        let decoded = try JSONSerialization.jsonObject(with: bytes) as? [String: String]
        #expect(decoded?["hello"] == "world")
    }

    @Test("save materializes an inline string body as raw text, not JSON-quoted")
    func saveMaterializesStringBodyAsRawText() async throws {
        let path = tempPath("materialize-text")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(variants: [ProjectVariant(name: "ok", status: 200, body: .string("hello world"))]))
        try await store.save()

        let project = await store.currentProject
        let bodyFile = try #require(project.endpoints[0].variants[0].bodyFile)
        #expect(bodyFile.hasSuffix(".txt"))
        let bytes = try await store.readFixture(bodyFile)
        #expect(String(decoding: bytes, as: UTF8.self) == "hello world")
    }

    @Test("save resolves fixture path collisions deterministically")
    func saveResolvesFixtureCollisions() async throws {
        let path = tempPath("materialize-collision")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        // "dup" and "dup " sanitize to the identical fixture-path hint ("dup"), so both variants
        // land on the same base path and must be disambiguated with a numeric suffix.
        try await store.addEndpoint(
            endpoint(
                variants: [
                    ProjectVariant(name: "dup", status: 200, body: .string("first")),
                    ProjectVariant(name: "dup ", status: 201, body: .string("second")),
                ]))
        try await store.save()

        let project = await store.currentProject
        let bodyFiles = project.endpoints[0].variants.compactMap(\.bodyFile)
        #expect(bodyFiles.count == 2)
        #expect(Set(bodyFiles).count == 2, "fixture paths must be unique: \(bodyFiles)")

        let firstBytes = try await store.readFixture(bodyFiles[0])
        let secondBytes = try await store.readFixture(bodyFiles[1])
        #expect(String(decoding: firstBytes, as: UTF8.self) == "first")
        #expect(String(decoding: secondBytes, as: UTF8.self) == "second")
    }

    @Test("save copies forward an existing referenced fixture across a second save")
    func saveCopiesForwardExistingFixture() async throws {
        let path = tempPath("copy-forward")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(
            endpoint(variants: [ProjectVariant(name: "ok", status: 200, body: .string("persist me"))]))
        try await store.save()

        let bodyFile = try #require(await store.currentProject.endpoints[0].variants[0].bodyFile)

        // A second, unrelated mutation + save must not lose the already-materialized fixture.
        try await store.addEndpoint(endpoint(id: "other", path: "/other"))
        try await store.save()

        let bytes = try await store.readFixture(bodyFile)
        #expect(String(decoding: bytes, as: UTF8.self) == "persist me")
    }

    @Test("collectOrphanedFixtures finds files no variant references")
    func collectsOrphanedFixtures() async throws {
        let path = tempPath("orphans")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()

        try await store.writeFixture(Data("orphan".utf8), relativePath: "fixtures/orphan.txt")

        let orphans = await store.collectOrphanedFixtures()
        #expect(orphans == ["fixtures/orphan.txt"])
    }

    @Test("writeFixture rejects a path outside fixtures/")
    func writeFixtureRejectsOutsidePath() async throws {
        let path = tempPath("fixture-outside")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        await #expect(throws: ProjectStoreError.self) {
            try await store.writeFixture(Data(), relativePath: "not-fixtures/evil.txt")
        }
    }

    @Test("writeFixture rejects path traversal")
    func writeFixtureRejectsTraversal() async throws {
        let path = tempPath("fixture-traversal")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)

        await #expect(throws: ProjectStoreError.self) {
            try await store.writeFixture(Data(), relativePath: "fixtures/../../evil.txt")
        }
    }

    // MARK: - Concurrency / external change detection

    @Test("save rejects when the bundle changed on disk since it was loaded")
    func saveRejectsExternalChange() async throws {
        let path = tempPath("external-change")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()

        // Simulate an external process (Studio, a manual edit) touching the bundle after this
        // store last saved it.
        let manifestFile = (path as NSString).appendingPathComponent("project.yml")
        var contents = try String(contentsOfFile: manifestFile, encoding: .utf8)
        contents += "\n# external edit\n"
        try contents.write(toFile: manifestFile, atomically: true, encoding: .utf8)

        // A mutation now fails fast, before touching the in-memory model — see
        // `mutationRejectsExternalChangeBeforeMutating` below. Bypass that guard here (by saving
        // directly with no prior mutation) to exercise save()'s own independent check.
        await #expect(throws: ProjectStoreError.self) {
            try await store.save()
        }

        // The failed save must not have clobbered the external edit.
        let stillThere = try String(contentsOfFile: manifestFile, encoding: .utf8)
        #expect(stillThere.contains("# external edit"))
    }

    @Test("a mutation fails fast — before touching the in-memory model — when the bundle changed on disk")
    func mutationRejectsExternalChangeBeforeMutating() async throws {
        let path = tempPath("external-change-mutation")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()

        // Simulate a hand-edit made while this MCP-style session still holds the bundle open —
        // the scenario that used to be caught only much later, at save time, if at all with
        // autosave off.
        let manifestFile = (path as NSString).appendingPathComponent("project.yml")
        var contents = try String(contentsOfFile: manifestFile, encoding: .utf8)
        contents += "\n# external edit\n"
        try contents.write(toFile: manifestFile, atomically: true, encoding: .utf8)

        await #expect(throws: ProjectStoreError.self) {
            try await store.addEndpoint(endpoint(id: "other", path: "/other"))
        }

        // The rejected mutation must not have been applied in memory.
        let endpoints = await store.currentProject.endpoints
        #expect(endpoints.map(\.id) == ["get-users"])

        // Every incremental mutation method shares the same guard.
        await #expect(throws: ProjectStoreError.self) {
            try await store.upsertVariant(endpointID: "get-users", ProjectVariant(name: "b", status: 500))
        }
        await #expect(throws: ProjectStoreError.self) {
            try await store.removeVariant(endpointID: "get-users", name: "default")
        }
        await #expect(throws: ProjectStoreError.self) {
            try await store.updateEndpoint(id: "get-users", endpoint())
        }
        await #expect(throws: ProjectStoreError.self) {
            try await store.removeEndpoint(id: "get-users")
        }
    }

    // MARK: - Transaction recovery

    @Test("init recovers a single interrupted-transaction backup")
    func initRecoversSingleBackup() async throws {
        let path = tempPath("recover-single")
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.addEndpoint(endpoint())
        try await store.save()
        defer { try? FileManager.default.removeItem(atPath: path) }

        // Simulate a crash between "moved destination to backup" and "moved staging to
        // destination": the destination is gone, only a backup sibling remains.
        let parent = (path as NSString).deletingLastPathComponent
        let base = (path as NSString).lastPathComponent
        let backupPath = (parent as NSString).appendingPathComponent(".\(base).backup-crash-test")
        try FileManager.default.moveItem(atPath: path, toPath: backupPath)
        defer { try? FileManager.default.removeItem(atPath: backupPath) }

        let recovered = try ProjectStore(path: path)
        let project = await recovered.currentProject
        #expect(project.endpoints.map(\.id) == ["get-users"])
        #expect(!FileManager.default.fileExists(atPath: backupPath))
    }

    @Test("init throws projectRecoveryRequired when multiple backups are ambiguous")
    func initThrowsOnAmbiguousRecovery() async throws {
        let path = tempPath("recover-ambiguous")
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.save()

        let parent = (path as NSString).deletingLastPathComponent
        let base = (path as NSString).lastPathComponent
        let backupA = (parent as NSString).appendingPathComponent(".\(base).backup-a")
        let backupB = (parent as NSString).appendingPathComponent(".\(base).backup-b")
        try FileManager.default.copyItem(atPath: path, toPath: backupA)
        try FileManager.default.moveItem(atPath: path, toPath: backupB)
        defer {
            try? FileManager.default.removeItem(atPath: backupA)
            try? FileManager.default.removeItem(atPath: backupB)
            try? FileManager.default.removeItem(atPath: path)
        }

        #expect(throws: ProjectStoreError.self) {
            _ = try ProjectStore(path: path)
        }
    }

    @Test("init cleans up stale staging and backup remnants when the destination is valid")
    func initCleansUpStaleRemnantsWhenDestinationValid() async throws {
        let path = tempPath("cleanup-remnants")
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.save()
        defer { try? FileManager.default.removeItem(atPath: path) }

        let parent = (path as NSString).deletingLastPathComponent
        let base = (path as NSString).lastPathComponent
        let staleStaging = (parent as NSString).appendingPathComponent(".\(base).staging-stale")
        try FileManager.default.createDirectory(atPath: staleStaging, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: staleStaging) }

        _ = try ProjectStore(path: path)
        #expect(!FileManager.default.fileExists(atPath: staleStaging))
    }

    // MARK: - Rename / delete

    /// Regression test: rename() reassigns projectPath to the new destination before calling
    /// save(), so save()'s own default sourceRoot (its own projectPath) pointed at the new,
    /// not-yet-populated directory instead of where the fixture actually still lived. Every
    /// existing test up to this one used an endpoint with no bodyFile, so this never fired.
    @Test("rename preserves a fixture referenced by an existing bodyFile")
    func renamePreservesExistingFixture() async throws {
        let oldPath = tempPath("rename-fixture-old")
        let newPath = tempPath("rename-fixture-new")
        defer {
            try? FileManager.default.removeItem(atPath: oldPath)
            try? FileManager.default.removeItem(atPath: newPath)
        }
        let store = try ProjectStore.create(manifest: manifest(), at: oldPath)
        try await store.addEndpoint(
            endpoint(variants: [ProjectVariant(name: "default", status: 200, body: .string("hello"))]))
        try await store.save()

        let bodyFile = try #require(await store.currentProject.endpoints.first?.variants.first?.bodyFile)

        try await store.rename(to: newPath)

        #expect(FileManager.default.fileExists(atPath: newPath + "/" + bodyFile))
    }

    @Test("rename persists at the new path and removes the old bundle")
    func renameMovesBundle() async throws {
        let oldPath = tempPath("rename-old")
        let newPath = tempPath("rename-new")
        defer {
            try? FileManager.default.removeItem(atPath: oldPath)
            try? FileManager.default.removeItem(atPath: newPath)
        }
        let store = try ProjectStore.create(manifest: manifest(), at: oldPath)
        try await store.addEndpoint(endpoint())
        try await store.save()

        try await store.rename(to: newPath)

        #expect(!FileManager.default.fileExists(atPath: oldPath))
        #expect(FileManager.default.fileExists(atPath: newPath))
        let project = await store.currentProject
        #expect(project.projectPath == (newPath as NSString).standardizingPath)
    }

    @Test("delete removes the on-disk bundle")
    func deleteRemovesBundle() async throws {
        let path = tempPath("delete")
        let store = try ProjectStore.create(manifest: manifest(), at: path)
        try await store.delete()
        #expect(!FileManager.default.fileExists(atPath: path))
    }
}
