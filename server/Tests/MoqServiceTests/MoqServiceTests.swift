import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat
@testable import MoqService

struct MoqServiceTests {
    let service = MoqService()

    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString).appendingPathComponent("service-\(label)-\(UUID().uuidString).moqproj")
    }

    func manifest(name: String = "Service Test") -> ProjectManifest {
        ProjectManifest(
            name: name,
            defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
    }

    // MARK: - Session lifecycle

    @Test("An unopened session raises E_UNKNOWN_SESSION")
    func unknownSessionRaisesUnknownSession() async {
        await #expect(throws: MoqServiceError.self) {
            _ = try await service.describeProject(handle: "does-not-exist")
        }
    }

    @Test("closeSession invalidates the handle")
    func closeSessionInvalidatesHandle() async throws {
        let handle = await service.openSession()
        let path = tempPath("close")
        defer { try? FileManager.default.removeItem(atPath: path) }
        _ = try await service.createProject(handle: handle, manifest: manifest(), path: path, force: false)

        await service.closeSession(handle)

        await #expect(throws: MoqServiceError.self) {
            _ = try await service.describeProject(handle: handle)
        }
    }

    @Test("Two sessions hold two independent open projects")
    func twoSessionsAreIndependent() async throws {
        let handleA = await service.openSession()
        let handleB = await service.openSession()
        let pathA = tempPath("a")
        let pathB = tempPath("b")
        defer {
            try? FileManager.default.removeItem(atPath: pathA)
            try? FileManager.default.removeItem(atPath: pathB)
        }

        _ = try await service.createProject(handle: handleA, manifest: manifest(name: "A"), path: pathA, force: false)
        _ = try await service.createProject(handle: handleB, manifest: manifest(name: "B"), path: pathB, force: false)

        let descriptionA = try await service.describeProject(handle: handleA)
        let descriptionB = try await service.describeProject(handle: handleB)
        #expect(descriptionA.name == "A")
        #expect(descriptionB.name == "B")
    }

    // MARK: - Reserved path

    @Test("upsertEndpoint rejects a reserved path")
    func upsertEndpointRejectsReservedPath() async throws {
        let handle = await service.openSession()
        let path = tempPath("reserved")
        defer { try? FileManager.default.removeItem(atPath: path) }
        _ = try await service.createProject(handle: handle, manifest: manifest(), path: path, force: false)

        let input = EndpointUpsertInput(
            id: "health-check", alias: nil, description: nil, referenceName: nil, method: "GET", path: "/health",
            tags: nil, auth: nil, requestRules: nil, operation: nil, network: nil, strictCallCount: nil)

        await #expect(throws: ProjectValidationInputError.self) {
            _ = try await service.upsertEndpoint(handle: handle, input: input, autosave: false)
        }
    }

    // MARK: - Stateless validate — the surface that has no MCP-tool equivalent

    @Test("Stateless validate flags an invalid in-memory project with no session or disk state")
    func statelessValidateFlagsInvalidProject() {
        let project = MoqProject(
            manifest: manifest(),
            endpoints: [],  // E_NO_ENDPOINTS
            projectPath: "/tmp/does-not-exist-\(UUID().uuidString)")

        let result = service.validateProject(project)

        #expect(result.errorCount > 0)
        #expect(result.diagnostics.contains { $0.code == "E_NO_ENDPOINTS" })
    }

    @Test("Stateless validate agrees with the store-backed validate for the same project")
    func statelessValidateAgreesWithSessionValidate() async throws {
        let handle = await service.openSession()
        let path = tempPath("agree")
        defer { try? FileManager.default.removeItem(atPath: path) }
        _ = try await service.createProject(handle: handle, manifest: manifest(), path: path, force: false)

        let input = EndpointUpsertInput(
            id: "get-a", alias: nil, description: nil, referenceName: nil, method: "GET", path: "/a", tags: nil,
            auth: nil, requestRules: nil, operation: nil, network: nil, strictCallCount: nil)
        _ = try await service.upsertEndpoint(handle: handle, input: input, autosave: false)

        let sessionResult = try await service.validateProject(handle: handle)
        let snapshot = try await service.projectSnapshot(handle: handle)
        let statelessResult = service.validateProject(snapshot)

        #expect(sessionResult.errorCount == statelessResult.errorCount)
        #expect(sessionResult.diagnostics.map(\.code) == statelessResult.diagnostics.map(\.code))
    }

    @Test("Stateless validate round-trips through JSON, the shape a JSON-RPC caller actually sends")
    func statelessValidateRoundTripsThroughJSON() throws {
        let project = MoqProject(manifest: manifest(), endpoints: [], projectPath: "/tmp/x")
        let data = try JSONEncoder().encode(project)
        let decoded = try JSONDecoder().decode(MoqProject.self, from: data)

        let result = service.validateProject(decoded)
        #expect(result.diagnostics.contains { $0.code == "E_NO_ENDPOINTS" })
    }
}
