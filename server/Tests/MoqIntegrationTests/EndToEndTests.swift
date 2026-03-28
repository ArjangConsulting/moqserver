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

@Suite("End-to-End: Spec → Server → Request → Response")
struct EndToEndTests {

    // MARK: - Helpers

    private func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    private func fixtureDirectory(_ name: String) -> String {
        Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!.path
    }

    private func parseAndServe(specName: String, config: ServerConfig? = nil) async throws -> (Application, InMemoryMockStore) {
        let data = try loadFixture(specName)
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)
        let endpoints = EndpointConverter.makeEndpoints(from: spec)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let app = try await buildApp(store: store, config: config)
        return (app, store)
    }

    // MARK: - Spec → Serve → GET

    @Test("Parse petstore.yaml → serve → GET /pets → 200 with JSON body")
    func petstoreListPets() async throws {
        let (app, _) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/json")

            let body = res.body.string
            #expect(body.contains("Fido"))
            #expect(body.contains("Whiskers"))
        }
    }

    @Test("Parse petstore.yaml → serve → POST /pets → 201 with created pet")
    func petstoreCreatePet() async throws {
        let (app, _) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .POST, "/pets") { res in
            #expect(res.status == .created)
            let body = res.body.string
            #expect(body.contains("Buddy"))
        }
    }

    // MARK: - Path Parameters

    @Test("GET /pets/{petId} → resolves path parameter via regex matching")
    func petstorePathParam() async throws {
        let (app, _) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/pets/42") { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Fido"))
        }
    }

    // MARK: - Auth Enforcement

    @Test("Spec with bearer auth → 401 without token, 200 with valid token")
    func specAuthEnforcement() async throws {
        let data = try loadFixture("petstore-auth.yaml")
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)
        let endpoints = EndpointConverter.makeEndpoints(from: spec)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let config = ServerConfig(
            auth: .init(bearerTokens: ["test-token-123"])
        )
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        // /pets requires bearer auth (global security) → 401 without token
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .unauthorized)
        }

        // 200 with valid bearer token
        try await withApp(app, .GET, "/pets", headers: ["Authorization": "Bearer test-token-123"]) { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Fido"))
        }

        // /public/health has security: [] (no auth) → 200 without token
        try await withApp(app, .GET, "/public/health") { res in
            #expect(res.status == .ok)
        }
    }

    // MARK: - Content Negotiation

    @Test("Multi-content spec → Accept: application/xml returns XML variant")
    func contentNegotiation() async throws {
        let (app, _) = try await parseAndServe(specName: "petstore-multi-content.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        // Default should return JSON
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType) ?? ""
            #expect(contentType.contains("json"))
        }

        // Accept: application/xml should return XML variant
        try await withApp(app, .GET, "/pets", headers: ["Accept": "application/xml"]) { res in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType) ?? ""
            #expect(contentType.contains("xml"))
        }
    }

    // MARK: - Admin Override → Serve

    @Test("Set variant override via store → subsequent requests use override")
    func adminVariantOverride() async throws {
        let (app, store) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        // Default GET /pets returns 200 variant
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Fido"))
        }

        // Set variant override to "error-500" via store (admin API equivalent)
        await store.setVariantOverride(for: "GET /pets", variant: "error-500")

        // Now GET /pets should return the 500 variant
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .internalServerError)
            let body = res.body.string
            #expect(body.contains("Internal server error"))
        }

        // Reset variant
        await store.resetVariantOverride(for: "GET /pets")

        // Back to default
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
        }
    }

    // MARK: - X-Mock-Variant Header

    @Test("X-Mock-Variant header selects specific variant")
    func xMockVariantHeader() async throws {
        let (app, _) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        // Select error-404 variant for GET /pets/{petId}
        try await withApp(app, .GET, "/pets/1", headers: ["X-Mock-Variant": "error-404"]) { res in
            #expect(res.status == .notFound)
            let body = res.body.string
            #expect(body.contains("Pet not found"))
        }
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

        // With valid token and required Accept header
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
        ]) { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Test User"))
        }

        // Select "empty" variant
        try await withApp(app, .GET, "/api/v1/users", headers: [
            "Authorization": "Bearer valid-token",
            "Accept": "application/json",
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
        let (app, _) = try await parseAndServe(specName: "petstore.yaml")
        defer { Task { try? await app.asyncShutdown() } }

        try await withApp(app, .GET, "/nonexistent/path") { res in
            #expect(res.status == .notFound)
            let body = res.body.string
            #expect(body.contains("endpoint_not_found"))

            // Verify it decodes as ErrorResponse
            let data = Data(body.utf8)
            let decoded = try? JSONDecoder().decode(ErrorResponse.self, from: data)
            #expect(decoded != nil)
            #expect(decoded?.code == "endpoint_not_found")
        }
    }

    // MARK: - Import → Save → Reload → Serve Round-Trip

    @Test("Parse spec → convert to project → write → reload → serve → verify responses")
    func importSaveServeRoundTrip() async throws {
        let data = try loadFixture("petstore.yaml")
        let parser = OpenAPIParser()
        let spec = try parser.parse(data: data)

        // Build project domain models from parsed spec
        let defaults = ProjectDefaults(
            auth: ProjectAuthConfig(type: .none, verify: false),
            network: NetworkBehavior()
        )
        let manifest = ProjectManifest(
            version: "1",
            name: "Round-Trip Test",
            description: "E2E round-trip test",
            defaults: defaults
        )

        let endpointDocs = spec.endpoints.map { parsed in
            let variants = parsed.responses.map { resp in
                var bodyValue: AnyCodableValue?
                if let bodyData = resp.body,
                   let jsonObj = try? JSONSerialization.jsonObject(with: bodyData) {
                    bodyValue = AnyCodableValue.fromFoundation(jsonObj)
                }

                return ProjectVariant(
                    name: resp.name,
                    status: resp.statusCode,
                    headers: resp.headers.isEmpty ? nil : Dictionary(
                        resp.headers.map { ($0.0, $0.1) },
                        uniquingKeysWith: { _, last in last }
                    ),
                    body: bodyValue
                )
            }

            let pathSlug = parsed.path
                .replacingOccurrences(of: "/", with: "-")
                .replacingOccurrences(of: "{", with: "")
                .replacingOccurrences(of: "}", with: "")
                .trimmingCharacters(in: CharacterSet(charactersIn: "-"))

            return EndpointDocument(
                id: "\(parsed.method.lowercased())-\(pathSlug)",
                method: parsed.method.uppercased(),
                path: parsed.path,
                variants: variants
            )
        }

        let project = MoqProject(
            manifest: manifest,
            endpoints: endpointDocs,
            projectPath: ""
        )

        // Write to temp directory
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("moqserver-e2e-\(UUID().uuidString)")
            .appendingPathExtension("moqproj")
            .path

        let writer = ProjectWriter()
        try writer.write(project, to: tempDir)
        defer { try? FileManager.default.removeItem(atPath: tempDir) }

        // Reload project
        let loader = ProjectLoader()
        let reloaded = try loader.load(from: tempDir)
        let endpoints = try ProjectToRuntimeConverter.convert(reloaded)

        let store = InMemoryMockStore()
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // Verify the round-tripped endpoints still serve correctly
        try await withApp(app, .GET, "/pets") { res in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("Fido"))
        }

        try await withApp(app, .GET, "/pets/1") { res in
            #expect(res.status == .ok)
        }
    }
}

// MARK: - AnyCodableValue Helpers

extension AnyCodableValue {
    /// Create an AnyCodableValue from a Foundation object (e.g. from JSONSerialization).
    static func fromFoundation(_ value: Any) -> AnyCodableValue {
        if value is NSNull {
            return .null
        } else if let bool = value as? Bool {
            return .bool(bool)
        } else if let int = value as? Int {
            return .int(int)
        } else if let double = value as? Double {
            return .double(double)
        } else if let string = value as? String {
            return .string(string)
        } else if let array = value as? [Any] {
            return .array(array.map { fromFoundation($0) })
        } else if let dict = value as? [String: Any] {
            return .object(dict.mapValues { fromFoundation($0) })
        }
        return .null
    }
}
