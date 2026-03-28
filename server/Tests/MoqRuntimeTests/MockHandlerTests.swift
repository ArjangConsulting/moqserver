import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor
@testable import MoqCore
@testable import MoqRuntime

@Suite("MockHandler Tests")
struct MockHandlerTests {
    func makeEndpoint(
        method: HTTPMethodValue,
        path: String,
        auth: AuthRequirement = .none,
        variants: [ResponseVariant]? = nil
    ) -> Endpoint {
        let defaultVariants = [
            ResponseVariant(
                name: "default",
                statusCode: .ok,
                headers: [("Content-Type", "application/json")],
                body: Data(#"{"ok":true}"#.utf8)
            )
        ]
        return Endpoint(
            key: EndpointKey(method: method, path: path),
            authRequirement: auth,
            variants: variants ?? defaultVariants
        )
    }

    // MARK: - Endpoint Not Found

    @Test("Returns 404 with structured error for unknown endpoint")
    func endpointNotFound() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/nonexistent") { res async in
            #expect(res.status == .notFound)
            let body = String(buffer: res.body)
            #expect(body.contains("endpoint_not_found"))
            #expect(body.contains("_admin"))
        }
    }

    // MARK: - Variant Selection

    @Test("X-Mock-Variant header selects specific variant")
    func variantHeaderSelection() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: [
            ResponseVariant(name: "default", statusCode: .ok, body: Data(#"{"status":"ok"}"#.utf8)),
            ResponseVariant(name: "error-500", statusCode: .internalServerError, body: Data(#"{"error":"fail"}"#.utf8)),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["X-Mock-Variant": "error-500"]) { res async in
            #expect(res.status == .internalServerError)
            let body = String(buffer: res.body)
            #expect(body.contains("fail"))
        }
    }

    @Test("Admin override takes precedence over default")
    func adminOverrideSelection() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: [
            ResponseVariant(name: "default", statusCode: .ok, body: Data("{}".utf8)),
            ResponseVariant(name: "error-500", statusCode: .internalServerError, body: Data("{}".utf8)),
        ]))
        await store.setVariantOverride(for: "GET /pets", variant: "error-500")

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets") { res async in
            #expect(res.status == .internalServerError)
        }
    }

    @Test("X-Mock-Variant header overrides admin override")
    func headerOverridesAdmin() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: [
            ResponseVariant(name: "default", statusCode: .ok, body: Data("{}".utf8)),
            ResponseVariant(name: "error-500", statusCode: .internalServerError, body: Data("{}".utf8)),
        ]))
        await store.setVariantOverride(for: "GET /pets", variant: "error-500")

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["X-Mock-Variant": "default"]) { res async in
            #expect(res.status == .ok)
        }
    }

    @Test("Config variant override is used when no header or admin override")
    func configOverride() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: [
            ResponseVariant(name: "default", statusCode: .ok, body: Data("{}".utf8)),
            ResponseVariant(name: "error-404", statusCode: .notFound, body: Data("{}".utf8)),
        ]))

        let config = ServerConfig(variantOverrides: ["GET /pets": "error-404"])
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets") { res async in
            #expect(res.status == .notFound)
        }
    }

    @Test("Unknown variant name falls back to default variant")
    func variantFallback() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // When X-Mock-Variant references a nonexistent name, handler falls back to default
        try await app.testing().test(.GET, "/pets", headers: ["X-Mock-Variant": "nonexistent"]) { res async in
            #expect(res.status == .ok)
        }
    }

    @Test("Variant not found when no variants match at all")
    func variantNotFoundNoMatch() async throws {
        let store = InMemoryMockStore()
        // Create endpoint with only a constrained variant that won't match
        await store.register(makeEndpoint(method: .get, path: "/constrained", variants: [
            ResponseVariant(
                name: "only-admin",
                statusCode: .ok,
                body: Data("{}".utf8),
                requestMatch: RequestMatch(headers: ["X-Role": "admin"])
            ),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // Request without the required header — no variant matches
        try await app.testing().test(.GET, "/constrained") { res async in
            #expect(res.status == .notFound)
            let body = String(buffer: res.body)
            #expect(body.contains("variant_not_found"))
        }
    }

    // MARK: - Response Building

    @Test("Response includes variant headers")
    func responseHeaders() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/test", variants: [
            ResponseVariant(
                name: "default",
                statusCode: .ok,
                headers: [("Content-Type", "application/json"), ("X-Custom", "value")],
                body: Data("{}".utf8)
            ),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/test") { res async in
            #expect(res.status == .ok)
            #expect(res.headers.first(name: "X-Custom") == "value")
        }
    }

    @Test("Response with nil body returns empty body")
    func emptyBody() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .delete, path: "/items", variants: [
            ResponseVariant(name: "default", statusCode: HTTPStatusCode(code: 204), body: nil),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.DELETE, "/items") { res async in
            #expect(res.status == .noContent)
            #expect(res.body.readableBytes == 0)
        }
    }

    // MARK: - Request Match Constraints

    @Test("Variant with requestMatch on query is selected when query matches")
    func requestMatchQuery() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/search", variants: [
            ResponseVariant(
                name: "default",
                statusCode: .ok,
                body: Data(#"{"results":"all"}"#.utf8)
            ),
            ResponseVariant(
                name: "filtered",
                statusCode: .ok,
                body: Data(#"{"results":"filtered"}"#.utf8),
                requestMatch: RequestMatch(query: ["type": "active"])
            ),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/search?type=active") { res async in
            let body = String(buffer: res.body)
            #expect(body.contains("filtered"))
        }

        try await app.testing().test(.GET, "/search") { res async in
            let body = String(buffer: res.body)
            #expect(body.contains("all"))
        }
    }

    @Test("Variant with requestMatch on headers is selected when header matches")
    func requestMatchHeaders() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/data", variants: [
            ResponseVariant(
                name: "default",
                statusCode: .ok,
                body: Data(#"{"format":"default"}"#.utf8)
            ),
            ResponseVariant(
                name: "admin",
                statusCode: .ok,
                body: Data(#"{"format":"admin"}"#.utf8),
                requestMatch: RequestMatch(headers: ["X-Role": "admin"])
            ),
        ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/data", headers: ["X-Role": "admin"]) { res async in
            let body = String(buffer: res.body)
            #expect(body.contains("admin"))
        }
    }

    // MARK: - Path Parameters

    @Test("Path parameters match via regex")
    func pathParameterMatching() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/users/{id}"))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/users/123") { res async in
            #expect(res.status == .ok)
        }

        try await app.testing().test(.GET, "/users/abc") { res async in
            #expect(res.status == .ok)
        }
    }

    // MARK: - GraphQL

    @Test("GraphQL POST to standard path resolves endpoint")
    func graphqlResolution() async throws {
        let store = InMemoryMockStore()
        let endpoint = Endpoint(
            key: EndpointKey(method: .post, path: "/graphql"),
            authRequirement: .none,
            variants: [ResponseVariant(name: "default", statusCode: .ok, body: Data(#"{"data":{}}"#.utf8))]
        )
        await store.register(endpoint)

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST,
            "/graphql",
            headers: ["Content-Type": "application/json"],
            body: ByteBuffer(string: #"{"query":"{ users { id } }"}"#)
        ) { res async in
            #expect(res.status == .ok)
        }
    }
}
