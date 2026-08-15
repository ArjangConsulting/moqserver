import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat
@testable import MoqService

/// `writeProject` is the whole-project counterpart to the incremental endpoint/variant mutation
/// calls, and the seam Studio's Kotlin client hangs its load/save off of — see
/// `format.FormatClient` / `RemoteProjectStore` on the Kotlin side. Covers both the
/// never-saved-before path (create) and the re-save path (open, replace, save), including that
/// re-save preserves crash safety and the on-disk-changed guard `ProjectStore` already provides.
struct WriteProjectTests {
    let service = MoqService()

    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString).appendingPathComponent(
            "write-project-\(label)-\(UUID().uuidString).moqproj")
    }

    func manifest(name: String = "WriteProject") -> ProjectManifest {
        ProjectManifest(
            name: name,
            defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
    }

    @Test("Writing a project to a path that doesn't exist yet creates it")
    func createsWhenAbsent() async throws {
        let path = tempPath("create")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let handle = await service.openSession()

        let project = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [ProjectVariant(name: "default", status: 200)])
            ],
            projectPath: path)

        let description = try await service.writeProject(handle: handle, project: project, force: false)
        #expect(description.endpointCount == 1)
        #expect(FileManager.default.fileExists(atPath: path + "/project.yml"))

        let reloaded = try ProjectStore(path: path)
        let reloadedProject = await reloaded.currentProject
        #expect(reloadedProject.endpoints.map(\.id) == ["get-a"])
    }

    @Test("Writing again to the same path replaces the endpoint set, not merges it")
    func rewriteReplacesEndpoints() async throws {
        let path = tempPath("rewrite")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let handle = await service.openSession()

        let first = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [ProjectVariant(name: "default", status: 200)])
            ],
            projectPath: path)
        _ = try await service.writeProject(handle: handle, project: first, force: false)

        let second = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-b", method: "GET", path: "/b",
                    variants: [ProjectVariant(name: "default", status: 200)])
            ],
            projectPath: path)
        let description = try await service.writeProject(handle: handle, project: second, force: false)

        #expect(description.endpointCount == 1)
        let endpointFiles = try FileManager.default.contentsOfDirectory(atPath: path + "/endpoints")
        #expect(endpointFiles == ["get-b.yml"], "get-a.yml must not survive a rewrite that no longer references it")
    }

    @Test("A fixture referenced by body_file, unchanged across a rewrite, survives it")
    func rewritePreservesUntouchedFixtures() async throws {
        let path = tempPath("preserve-fixture")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let handle = await service.openSession()

        let withInlineBody = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [
                        ProjectVariant(name: "default", status: 200, body: .string("hello"))
                    ])
            ],
            projectPath: path)
        _ = try await service.writeProject(handle: handle, project: withInlineBody, force: false)

        // Read back what the store actually externalized the inline body to.
        let afterFirstWrite = try await service.projectSnapshot(handle: handle)
        let bodyFile = try #require(afterFirstWrite.endpoints.first?.variants.first?.bodyFile)

        // Rewrite referencing the SAME bodyFile (as Studio would: it doesn't invent new paths,
        // it round-trips what the store told it) plus an unrelated new endpoint.
        let secondEndpoint = EndpointDocument(
            id: "get-a", method: "GET", path: "/a",
            variants: [ProjectVariant(name: "default", status: 200, bodyFile: bodyFile)])
        let rewritten = MoqProject(manifest: manifest(), endpoints: [secondEndpoint], projectPath: path)
        _ = try await service.writeProject(handle: handle, project: rewritten, force: false)

        #expect(FileManager.default.fileExists(atPath: path + "/" + bodyFile))
    }

    /// Regression test: writeProject's Save As path (retargeting to a directory the session
    /// wasn't already open at) used to look for pre-existing bodyFile fixtures under the *new*
    /// target directory instead of where the session's store actually had them — every existing
    /// writeProject test up to this one reused the same path for both writes, so the bug (and
    /// its fix, threading sourceRoot through to ProjectStore.save) was never exercised. This is
    /// also exactly the scenario that broke Kotlin's `ProjectRepositoryTest` (load, then Save As
    /// to a fresh directory) — this is the Swift-side pin for the same fix.
    @Test("Save As to a directory this session wasn't open at preserves existing fixtures")
    func saveAsToNewDirectoryPreservesFixtures() async throws {
        let originalPath = tempPath("save-as-original")
        let newPath = tempPath("save-as-new")
        defer {
            try? FileManager.default.removeItem(atPath: originalPath)
            try? FileManager.default.removeItem(atPath: newPath)
        }
        let handle = await service.openSession()

        let withInlineBody = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [ProjectVariant(name: "default", status: 200, body: .string("hello"))])
            ],
            projectPath: originalPath)
        _ = try await service.writeProject(handle: handle, project: withInlineBody, force: false)
        let afterFirstWrite = try await service.projectSnapshot(handle: handle)
        let bodyFile = try #require(afterFirstWrite.endpoints.first?.variants.first?.bodyFile)

        // Same handle (session still open at originalPath), same bodyFile reference, new path —
        // exactly what ProjectRepository.save(project, newPath) sends on a Kotlin-side Save As.
        let retargeted = MoqProject(
            manifest: afterFirstWrite.manifest, endpoints: afterFirstWrite.endpoints, projectPath: newPath)
        _ = try await service.writeProject(handle: handle, project: retargeted, force: false)

        #expect(FileManager.default.fileExists(atPath: newPath + "/" + bodyFile))
    }

    @Test("force: false rejects overwriting a project that changed on disk since it was last read")
    func rejectsConcurrentDiskChange() async throws {
        let path = tempPath("concurrent")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let handle = await service.openSession()

        let initial = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [ProjectVariant(name: "default", status: 200)])
            ],
            projectPath: path)
        _ = try await service.writeProject(handle: handle, project: initial, force: false)

        // A second, independent process/session touches the bundle after our session last saw it.
        let otherStore = try ProjectStore(path: path)
        try await otherStore.addEndpoint(
            EndpointDocument(
                id: "get-external", method: "GET", path: "/external",
                variants: [ProjectVariant(name: "default", status: 200)]))
        try await otherStore.save()

        let ourEdit = MoqProject(
            manifest: manifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-a", method: "GET", path: "/a",
                    variants: [ProjectVariant(name: "default", status: 201)])
            ],
            projectPath: path)

        await #expect(throws: ProjectStoreError.self) {
            _ = try await service.writeProject(handle: handle, project: ourEdit, force: false)
        }

        // The externally added endpoint must have survived the rejected write.
        let onDisk = try ProjectStore(path: path)
        let onDiskProject = await onDisk.currentProject
        #expect(onDiskProject.endpoints.map(\.id).sorted() == ["get-a", "get-external"])
    }
}
