import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat
@testable import MoqMCP

struct ProjectSessionTests {
    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString).appendingPathComponent("mcp-session-\(label)-\(UUID().uuidString).moqproj")
    }

    func manifest() -> ProjectManifest {
        ProjectManifest(
            name: "Session Test",
            defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
    }

    @Test("currentStore throws before any project is opened")
    func currentStoreThrowsWhenClosed() async {
        let session = ProjectSession()
        await #expect(throws: SessionError.self) {
            _ = try await session.currentStore()
        }
    }

    @Test("create opens the project and clears dirty")
    func createOpensProject() async throws {
        let path = tempPath("create")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let session = ProjectSession()

        try await session.create(manifest: manifest(), at: path, force: false)
        #expect(await session.isOpen)
        #expect(await !session.isDirty)
    }

    @Test("recordMutation marks dirty and, with autosave, saves and clears dirty")
    func recordMutationAutosaves() async throws {
        let path = tempPath("autosave")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let session = ProjectSession()
        try await session.create(manifest: manifest(), at: path, force: false)

        let store = try await session.currentStore()
        try await store.addEndpoint(EndpointDocument(id: "get-a", method: "GET", path: "/a", variants: []))
        try await session.recordMutation(autosave: true)

        #expect(await !session.isDirty)
        let reloaded = try ProjectStore(path: path)
        let project = await reloaded.currentProject
        #expect(project.endpoints.map(\.id) == ["get-a"])
    }

    @Test("recordMutation without autosave stays dirty and does not persist")
    func recordMutationWithoutAutosaveStaysDirty() async throws {
        let path = tempPath("no-autosave")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let session = ProjectSession()
        try await session.create(manifest: manifest(), at: path, force: false)

        let store = try await session.currentStore()
        try await store.addEndpoint(EndpointDocument(id: "get-a", method: "GET", path: "/a", variants: []))
        try await session.recordMutation(autosave: false)

        #expect(await session.isDirty)
        let reloaded = try ProjectStore(path: path)
        let project = await reloaded.currentProject
        #expect(project.endpoints.isEmpty, "unsaved mutation must not have reached disk")
    }

    @Test("open refuses to discard unsaved changes without force")
    func openRefusesUnsavedChangesWithoutForce() async throws {
        let originalPath = tempPath("original")
        let otherPath = tempPath("other")
        defer {
            try? FileManager.default.removeItem(atPath: originalPath)
            try? FileManager.default.removeItem(atPath: otherPath)
        }
        let session = ProjectSession()
        try await session.create(manifest: manifest(), at: originalPath, force: false)
        _ = try ProjectStore.create(manifest: manifest(), at: otherPath)

        let store = try await session.currentStore()
        try await store.addEndpoint(EndpointDocument(id: "get-a", method: "GET", path: "/a", variants: []))
        try await session.recordMutation(autosave: false)

        await #expect(throws: SessionError.self) {
            try await session.open(path: otherPath, force: false)
        }
        // The dirty original project must still be the one open.
        let stillOriginal = try await session.currentStore()
        let project = await stillOriginal.currentProject
        #expect(project.projectPath == (originalPath as NSString).standardizingPath)
    }

    @Test("open with force discards unsaved changes")
    func openWithForceDiscardsUnsavedChanges() async throws {
        let originalPath = tempPath("original-force")
        let otherPath = tempPath("other-force")
        defer {
            try? FileManager.default.removeItem(atPath: originalPath)
            try? FileManager.default.removeItem(atPath: otherPath)
        }
        let session = ProjectSession()
        try await session.create(manifest: manifest(), at: originalPath, force: false)
        let store = try await session.currentStore()
        try await store.addEndpoint(EndpointDocument(id: "get-a", method: "GET", path: "/a", variants: []))
        try await session.recordMutation(autosave: false)

        _ = try ProjectStore.create(manifest: manifest(), at: otherPath)
        try await session.open(path: otherPath, force: true)

        let opened = try await session.currentStore()
        let project = await opened.currentProject
        #expect(project.projectPath == (otherPath as NSString).standardizingPath)
        #expect(await !session.isDirty)
    }

    @Test("save clears dirty and requires an open project")
    func saveClearsDirty() async throws {
        let session = ProjectSession()
        await #expect(throws: SessionError.self) {
            try await session.save()
        }

        let path = tempPath("save")
        defer { try? FileManager.default.removeItem(atPath: path) }
        try await session.create(manifest: manifest(), at: path, force: false)
        let store = try await session.currentStore()
        try await store.addEndpoint(EndpointDocument(id: "get-a", method: "GET", path: "/a", variants: []))
        try await session.recordMutation(autosave: false)
        #expect(await session.isDirty)

        try await session.save()
        #expect(await !session.isDirty)
    }
}
