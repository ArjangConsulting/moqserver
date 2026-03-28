import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor
@testable import MoqCore
@testable import MoqRuntime

@Suite("Auth Validation & Token Endpoint - Integration")
struct AuthValidationTests {
    let config = ServerConfig(
        auth: .init(
            bearerTokens: ["valid-bearer"],
            basicCredentials: [.init(username: "admin", password: "pass")],
            apiKeys: ["X-API-Key": "valid-key"],
            oauth2Tokens: ["valid-oauth-token"],
            oauth2Clients: [.init(clientId: "client1", clientSecret: "secret1")],
            oauth2TokenScopes: ["valid-oauth-token": ["read", "write:pets"]]
        )
    )

    func makeStore(endpoints: [Endpoint]) async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        for e in endpoints { await store.register(e) }
        return store
    }

    func makeEndpoint(
        method: HTTPMethodValue,
        path: String,
        auth: AuthRequirement,
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = []
    ) -> Endpoint {
        Endpoint(
            key: EndpointKey(method: method, path: path),
            authRequirement: auth,
            variants: [ResponseVariant(name: "default", body: Data(#"{"ok":true}"#.utf8))],
            requiredQueryParameters: requiredQueryParameters,
            requiredHeaders: requiredHeaders,
            requiresBody: requiresBody,
            acceptedContentTypes: acceptedContentTypes
        )
    }

    // MARK: - OAuth2 Validation

    @Test("OAuth2 endpoint rejects missing token with WWW-Authenticate")
    func oauth2RejectsMissingToken() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/secured", auth: .oauth2(scopes: ["read"]))
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/secured") { res async in
            #expect(res.status == .unauthorized)
            let wwwAuth = res.headers.first(name: "WWW-Authenticate")
            #expect(wwwAuth != nil)
            #expect(wwwAuth?.contains("Bearer") == true)
            #expect(wwwAuth?.contains("scope=\"read\"") == true)
        }
    }

    @Test("OAuth2 endpoint accepts valid token")
    func oauth2AcceptsValidToken() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/secured", auth: .oauth2(scopes: ["read"]))
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/secured", headers: ["Authorization": "Bearer valid-oauth-token"]) { res async in
            #expect(res.status == .ok)
        }
    }

    @Test("OAuth2 endpoint rejects invalid token")
    func oauth2RejectsInvalidToken() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/secured", auth: .oauth2(scopes: []))
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/secured", headers: ["Authorization": "Bearer wrong-token"]) { res async in
            #expect(res.status == .unauthorized)
            let wwwAuth = res.headers.first(name: "WWW-Authenticate")
            #expect(wwwAuth?.contains("invalid_token") == true)
        }
    }

    @Test("OAuth2 endpoint rejects token without required scope")
    func oauth2RejectsInsufficientScope() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/secured-scope", auth: .oauth2(scopes: ["admin"]))
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/secured-scope", headers: ["Authorization": "Bearer valid-oauth-token"]) { res async in
            #expect(res.status == .forbidden)
            let wwwAuth = res.headers.first(name: "WWW-Authenticate")
            #expect(wwwAuth?.contains("insufficient_scope") == true)
        }
    }

    // MARK: - Bearer Validation with WWW-Authenticate

    @Test("Bearer auth returns WWW-Authenticate header")
    func bearerWwwAuthenticate() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/bearer", auth: .bearer)
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/bearer") { res async in
            #expect(res.status == .unauthorized)
            let wwwAuth = res.headers.first(name: "WWW-Authenticate")
            #expect(wwwAuth == "Bearer realm=\"mock-server\"")
        }
    }

    // MARK: - Basic Auth with WWW-Authenticate

    @Test("Basic auth returns WWW-Authenticate header")
    func basicWwwAuthenticate() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/basic", auth: .basic)
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/basic") { res async in
            #expect(res.status == .unauthorized)
            let wwwAuth = res.headers.first(name: "WWW-Authenticate")
            #expect(wwwAuth == "Basic realm=\"mock-server\"")
        }
    }

    @Test("Basic auth accepts valid credentials")
    func basicAcceptsValid() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/basic", auth: .basic)
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        let creds = Data("admin:pass".utf8).base64EncodedString()
        try await app.testing().test(.GET, "/basic", headers: ["Authorization": "Basic \(creds)"]) { res async in
            #expect(res.status == .ok)
        }
    }

    // MARK: - Request Validation

    @Test("Endpoint validates required query parameters")
    func requiredQueryValidation() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/validate-query", auth: .none, requiredQueryParameters: ["id"])
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/validate-query") { res async in
            #expect(res.status == .badRequest)
            #expect(res.body.string.contains("Missing required query parameter"))
        }
    }

    @Test("Endpoint validates required headers")
    func requiredHeaderValidation() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(method: .get, path: "/validate-header", auth: .none, requiredHeaders: ["X-Trace-Id"])
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/validate-header") { res async in
            #expect(res.status == .badRequest)
            #expect(res.body.string.contains("Missing required header"))
        }
    }

    @Test("Endpoint validates required body and content type")
    func requestBodyValidation() async throws {
        let store = await makeStore(endpoints: [
            makeEndpoint(
                method: .post,
                path: "/validate-body",
                auth: .none,
                requiresBody: true,
                acceptedContentTypes: ["application/json"]
            )
        ])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "/validate-body") { res async in
            #expect(res.status == .badRequest)
        }

        try await app.testing().test(
            .POST,
            "/validate-body",
            headers: ["Content-Type": "text/plain"],
            body: ByteBuffer(string: "x")
        ) { res async in
            #expect(res.status == .unsupportedMediaType)
        }
    }

    // MARK: - Token Endpoint

    @Test("Token endpoint issues token for client_credentials grant")
    func tokenClientCredentials() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST, "/_auth/token",
            headers: ["Content-Type": "application/x-www-form-urlencoded"],
            body: ByteBuffer(string: "grant_type=client_credentials&client_id=client1&client_secret=secret1")
        ) { res async in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("access_token"))
            #expect(body.contains("Bearer"))
            #expect(body.contains("expires_in"))
            #expect(body.contains("valid-oauth-token"))
        }
    }

    @Test("Token endpoint rejects invalid client credentials")
    func tokenRejectsInvalidClient() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST, "/_auth/token",
            headers: ["Content-Type": "application/x-www-form-urlencoded"],
            body: ByteBuffer(string: "grant_type=client_credentials&client_id=wrong&client_secret=wrong")
        ) { res async in
            #expect(res.status == .badRequest)
            let body = res.body.string
            #expect(body.contains("invalid_client"))
        }
    }

    @Test("Token endpoint supports password grant")
    func tokenPasswordGrant() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST, "/_auth/token",
            headers: ["Content-Type": "application/x-www-form-urlencoded"],
            body: ByteBuffer(string: "grant_type=password&username=admin&password=pass")
        ) { res async in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("access_token"))
        }
    }

    @Test("Token endpoint supports authorization_code grant")
    func tokenAuthCodeGrant() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST, "/_auth/token",
            headers: ["Content-Type": "application/x-www-form-urlencoded"],
            body: ByteBuffer(string: "grant_type=authorization_code&code=any-code&redirect_uri=http://localhost/callback")
        ) { res async in
            #expect(res.status == .ok)
            let body = res.body.string
            #expect(body.contains("access_token"))
        }
    }

    @Test("Authorize endpoint redirects with code")
    func authorizeRedirects() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_auth/authorize?redirect_uri=http://example.com/cb&state=xyz") { res async in
            #expect(res.status == .found)
            let location = res.headers.first(name: "Location")
            #expect(location != nil)
            #expect(location?.contains("http://example.com/cb?code=mock-auth-code-") == true)
            #expect(location?.contains("state=xyz") == true)
        }
    }
}
