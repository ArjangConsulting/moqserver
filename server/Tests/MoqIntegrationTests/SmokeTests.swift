import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor
@testable import MoqCore
@testable import MoqFormat
@testable import MoqParsing
@testable import MoqRuntime

/// Run Vapor requests through the swift-testing helper.
private func withApp(_ app: Application, _ method: HTTPMethod, _ path: String,
                     headers: HTTPHeaders = [:],
                     check: @Sendable (TestingHTTPResponse) async throws -> Void) async throws {
    try await app.testing().test(method, path, headers: headers, afterResponse: check)
}

@Suite("Smoke Tests — Common Real-World Scenarios")
struct SmokeTests {

    private func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    private func fixtureDirectory(_ name: String) -> String {
        Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!.path
    }

    // MARK: - Petstore Smoke

    @Test("Smoke: Load petstore spec, verify all endpoints registered and respond")
    func petstoreSmokeTest() async throws {
        let data = try loadFixture("petstore.yaml")
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)
        let endpoints = EndpointConverter.makeEndpoints(from: spec)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        // Verify all endpoints registered
        let allEndpoints = await store.allEndpoints()
        #expect(allEndpoints.count == 3) // GET /pets, POST /pets, GET /pets/{petId}

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // All endpoints respond with expected status codes
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
        }

        try await withApp(app, .POST, "/pets") { res in
            #expect(res.status == .created)
        }

        try await withApp(app, .GET, "/pets/1") { res in
            #expect(res.status == .ok)
        }
    }

    // MARK: - Project Round-Trip

    @Test("Smoke: Load sample project, serve, request each endpoint type")
    func projectRoundTripSmoke() async throws {
        let projectPath = fixtureDirectory("sample-app.moqproj")
        let loader = ProjectLoader()
        let project = try loader.load(from: projectPath)

        #expect(project.manifest.name == "Sample App API Mock")
        #expect(project.endpoints.count == 3)

        let endpoints = try ProjectToRuntimeConverter.convert(project)
        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let config = ServerConfig(auth: .init(bearerTokens: ["valid-token"]))
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        // REST endpoint with auth
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
            "Cookie": "session_id=smoke-test",
        ]) { res in
            #expect(res.status == .ok)
        }

        // GraphQL endpoint — verify it's registered in the store
        let allEndpoints = await store.allEndpoints()
        let graphqlEndpoints = allEndpoints.filter { $0.key.path == "/graphql" }
        #expect(!graphqlEndpoints.isEmpty)
    }

    // MARK: - Config with Variant Overrides

    @Test("Smoke: Config with variant overrides applied at startup")
    func configVariantOverrides() async throws {
        let data = try loadFixture("petstore.yaml")
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)
        let endpoints = EndpointConverter.makeEndpoints(from: spec)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let config = ServerConfig(
            variantOverrides: ["GET /pets": "error-500"]
        )
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        // Config override should make GET /pets return 500
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .internalServerError)
        }

        // Other endpoints unaffected
        try await withApp(app, .GET, "/pets/1") { res in
            #expect(res.status == .ok)
        }
    }

    // MARK: - Error Response Format

    @Test("Smoke: All error responses are structured JSON with code field")
    func errorResponseFormat() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // 404 for nonexistent endpoint
        try await withApp(app, .GET, "/does-not-exist") { res in
            #expect(res.status == .notFound)
            let data = Data(res.body.string.utf8)
            let error = try JSONDecoder().decode(ErrorResponse.self, from: data)
            #expect(error.code == "endpoint_not_found")
            #expect(!error.error.isEmpty)
        }
    }

    // MARK: - HAR Import

    @Test("Smoke: Parse HAR fixture, verify endpoints extracted")
    func harImportSmoke() async throws {
        let data = try loadFixture("sample.har")
        let parser = HARParser()
        let spec = try parser.parse(data: data)

        #expect(!spec.endpoints.isEmpty)

        // Verify endpoints have responses
        for endpoint in spec.endpoints {
            #expect(!endpoint.responses.isEmpty)
        }

        // Can convert to runtime endpoints
        let endpoints = EndpointConverter.makeEndpoints(from: spec)
        #expect(endpoints.count == spec.endpoints.count)
    }

    // MARK: - Spec Validator

    @Test("Smoke: Spec validation produces warnings for known issues")
    func specValidation() async throws {
        let data = try loadFixture("petstore.yaml")
        let validator = OpenAPISpecValidator()
        let diagnostics = validator.validate(data: data)

        // Petstore spec is valid — no errors expected
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }

    // MARK: - Admin API Endpoint Listing

    @Test("Smoke: Admin API lists all registered endpoints")
    func adminEndpointListing() async throws {
        let data = try loadFixture("petstore.yaml")
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)
        let endpoints = EndpointConverter.makeEndpoints(from: spec)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/_admin/endpoints") { res in
            #expect(res.status == .ok)
            let data = Data(res.body.string.utf8)
            let items = try JSONDecoder().decode([EndpointListItem].self, from: data)
            #expect(items.count == 3)

            let methods = Set(items.map(\.method))
            #expect(methods.contains("GET"))
            #expect(methods.contains("POST"))
        }
    }
}
