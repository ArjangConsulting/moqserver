import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

@Suite("Server config validation")
struct ServerConfigValidationTests {

    @Test("Empty admin block is rejected")
    func emptyAdminBlockRejected() {
        let config = ServerConfig(admin: .init(bearerToken: nil, apiKeyHeader: nil, apiKey: nil))
        #expect(!config.validationErrors().isEmpty)
    }

    @Test("Admin block with bearer token is valid")
    func adminWithBearerTokenValid() {
        let config = ServerConfig(admin: .init(bearerToken: "token"))
        #expect(config.validationErrors().isEmpty)
    }

    @Test("Admin block with API key is valid")
    func adminWithApiKeyValid() {
        let config = ServerConfig(admin: .init(apiKeyHeader: "X-Admin-Key", apiKey: "key"))
        #expect(config.validationErrors().isEmpty)
    }

    @Test("Config without admin block is valid")
    func noAdminBlockValid() {
        #expect(ServerConfig().validationErrors().isEmpty)
    }
}

@Suite("ConfigLoader decode errors")
struct ConfigLoaderErrorTests {

    @Test("Undecodable YAML config reports both decode attempts")
    func undecodableYAMLReportsBothErrors() throws {
        let path = NSTemporaryDirectory() + "moqserver-bad-config-\(UUID().uuidString).yaml"
        try "globalDelay: [not, a, number]".write(toFile: path, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: path) }

        do {
            _ = try ConfigLoader().load(from: path)
            Issue.record("Expected ConfigLoaderError.invalidConfig")
        } catch let error as ConfigLoaderError {
            let description = error.description
            #expect(description.contains("YAML:"))
            #expect(description.contains("JSON:"))
        }
    }

    @Test("Malformed JSON config reports a JSON error, not a YAML fallback")
    func malformedJSONReportsJSONError() throws {
        let path = NSTemporaryDirectory() + "moqserver-bad-config-\(UUID().uuidString).json"
        try "{ not json".write(toFile: path, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: path) }

        do {
            _ = try ConfigLoader().load(from: path)
            Issue.record("Expected ConfigLoaderError.invalidConfig")
        } catch let error as ConfigLoaderError {
            #expect(error.description.contains("JSON:"))
        }
    }
}

@Suite("Error middleware preserves abort headers")
struct AbortHeaderPreservationTests {

    @Test("Admin 401 includes WWW-Authenticate challenge")
    func adminUnauthorizedIncludesChallenge() async throws {
        let store = InMemoryMockStore()
        let config = ServerConfig(admin: .init(bearerToken: "admin-token"))
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            #expect(res.status == .unauthorized)
            #expect(res.headers.first(name: "WWW-Authenticate") == "Bearer realm=\"mock-server-admin\"")
        }
    }
}

@Suite("Auth router authorize endpoint encoding")
struct AuthorizeEncodingTests {

    @Test("State value is percent-encoded in the redirect")
    func stateIsPercentEncoded() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        let state = "abc def&x=1"
        let encodedState = state.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        try await app.testing().test(
            .GET,
            "/_auth/authorize?response_type=code&redirect_uri=http://localhost/callback&state=\(encodedState)"
        ) { res async in
            #expect(res.status == .found)
            let location = res.headers.first(name: .location) ?? ""
            let components = URLComponents(string: location)
            let stateItem = components?.queryItems?.first { $0.name == "state" }
            #expect(stateItem?.value == state)
            #expect(components?.queryItems?.contains { $0.name == "code" } == true)
        }
    }

    @Test("Invalid redirect_uri is rejected instead of reflected")
    func invalidRedirectURIRejected() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        let badURI = "ht tp://bad uri".addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        try await app.testing().test(.GET, "/_auth/authorize?response_type=code&redirect_uri=\(badURI)") { res async in
            #expect(res.status == .badRequest)
        }
    }
}
