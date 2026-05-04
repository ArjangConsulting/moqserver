import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor
@testable import MoqCore
@testable import MoqFormat
@testable import MoqRuntime

/// Run Vapor requests through the swift-testing helper.
private func withApp(_ app: Application, _ method: HTTPMethod, _ path: String,
                     headers: HTTPHeaders = [:],
                     check: @Sendable (TestingHTTPResponse) async throws -> Void) async throws {
    try await app.testing().test(method, path, headers: headers, afterResponse: check)
}

@Suite("End-to-End: Project → Server → Request → Response")
struct EndToEndTests {

    // MARK: - Helpers

    private func fixtureDirectory(_ name: String) -> String {
        Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!.path
    }

    // MARK: - Project → Serve

    @Test("Load .moqproj → convert → serve → request endpoints")
    func projectToServe() async throws {
        let projectPath = fixtureDirectory("sample-app.moqproj")
        let loader = ProjectLoader()
        let project = try loader.load(from: projectPath)
        let endpoints = try ProjectToRuntimeConverter.convert(project)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let config = ServerConfig(
            auth: .init(bearerTokens: ["valid-token"])
        )
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        // list-users endpoint requires bearer auth
        try await withApp(app, .GET, "/api/v1/users") { res in
            #expect(res.status == .unauthorized)
        }

        // Missing cookie fails before success path
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
        ]) { res in
            #expect(res.status == .badRequest)
            #expect(res.body.string.contains("missing_required_cookie"))
        }

        // With valid token, required Accept header, and cookie
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
            "Cookie": "session_id=abc123",
        ]) { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Test User"))
        }

        // Select "empty" variant
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
            "Cookie": "session_id=abc123",
            "X-Mock-Variant": "empty",
        ]) { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("\"total\""))
        }
    }

    // MARK: - Structured Error Responses

    @Test("Request to unknown endpoint returns structured ErrorResponse JSON")
    func structuredErrorResponse() async throws {
        let projectPath = fixtureDirectory("sample-app.moqproj")
        let loader = ProjectLoader()
        let project = try loader.load(from: projectPath)
        let endpoints = try ProjectToRuntimeConverter.convert(project)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/nonexistent/path") { res in
            #expect(res.status == .notFound)
            let body = res.body.string
            #expect(body.contains("endpoint_not_found"))

            let data = Data(body.utf8)
            let decoded = try? JSONDecoder().decode(ErrorResponse.self, from: data)
            #expect(decoded != nil)
            #expect(decoded?.code == "endpoint_not_found")
        }
    }

    // MARK: - Write → Reload → Serve Round-Trip

    @Test("Write .moqproj → reload → serve → verify responses")
    func writeReloadServeRoundTrip() async throws {
        let projectPath = fixtureDirectory("sample-app.moqproj")
        let loader = ProjectLoader()
        let original = try loader.load(from: projectPath)

        // Write to a temp directory
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("round-trip-\(UUID().uuidString)")
            .appendingPathExtension("moqproj")
            .path

        let writer = ProjectWriter()
        try writer.write(original, to: tempDir)
        defer { try? FileManager.default.removeItem(atPath: tempDir) }

        // Copy fixture files so body_file references resolve after writing
        let srcFixtures = (projectPath as NSString).appendingPathComponent("fixtures")
        let dstFixtures = (tempDir as NSString).appendingPathComponent("fixtures")
        if FileManager.default.fileExists(atPath: srcFixtures) {
            let items = try FileManager.default.contentsOfDirectory(atPath: srcFixtures)
            try FileManager.default.createDirectory(atPath: dstFixtures, withIntermediateDirectories: true)
            for item in items {
                let src = (srcFixtures as NSString).appendingPathComponent(item)
                let dst = (dstFixtures as NSString).appendingPathComponent(item)
                if !FileManager.default.fileExists(atPath: dst) {
                    try FileManager.default.copyItem(atPath: src, toPath: dst)
                }
            }
        }

        // Reload and serve
        let reloaded = try loader.load(from: tempDir)
        let endpoints = try ProjectToRuntimeConverter.convert(reloaded)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let config = ServerConfig(auth: .init(bearerTokens: ["valid-token"]))
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
            "Cookie": "session_id=abc123",
        ]) { res in
            #expect(res.status == .ok)
            #expect(res.body.string.contains("Test User"))
        }
    }
}
