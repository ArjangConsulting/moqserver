import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct MockHandlerTests {

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

    @Test("Health route reports readiness")
    func healthRoute() async throws {
        let app = try await buildApp(store: InMemoryMockStore())
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/health") { res async in
            #expect(res.status == .ok)
            #expect(res.body.string.contains("ready"))
        }
    }

    // MARK: - Variant Selection

    @Test("X-Mock-Variant header selects specific variant")
    func variantHeaderSelection() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
                    ResponseVariant(name: "default", statusCode: .ok, body: Data(#"{"status":"ok"}"#.utf8)),
                    ResponseVariant(
                        name: "error-500", statusCode: .internalServerError, body: Data(#"{"error":"fail"}"#.utf8)),
                ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["X-Mock-Variant": "error-500"]) { res async in
            #expect(res.status == .internalServerError)
            let body = String(buffer: res.body)
            #expect(body.contains("fail"))
        }
    }

    @Test("X-Mock-Variant can select variant by reference name")
    func variantHeaderSelectionByReferenceName() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
                    ResponseVariant(
                        name: "Default", referenceName: "defaultVariant", statusCode: .ok,
                        body: Data(#"{"status":"ok"}"#.utf8)),
                    ResponseVariant(
                        name: "Server Error", referenceName: "serverError", statusCode: .internalServerError,
                        body: Data(#"{"error":"fail"}"#.utf8)),
                ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["X-Mock-Variant": "serverError"]) { res async in
            #expect(res.status == .internalServerError)
            let body = String(buffer: res.body)
            #expect(body.contains("fail"))
        }
    }

    @Test("Admin override takes precedence over default")
    func adminOverrideSelection() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
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
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
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
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
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
        await store.register(makeTestEndpoint(method: .get, path: "/pets"))

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
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/constrained",
                variants: [
                    ResponseVariant(
                        name: "only-admin",
                        statusCode: .ok,
                        body: Data("{}".utf8),
                        requestMatch: RequestMatch(headers: ["X-Role": "admin"])
                    )
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
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/test",
                variants: [
                    ResponseVariant(
                        name: "default",
                        statusCode: .ok,
                        headers: [("Content-Type", "application/json"), ("X-Custom", "value")],
                        body: Data("{}".utf8)
                    )
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
        await store.register(
            makeTestEndpoint(
                method: .delete, path: "/items",
                variants: [
                    ResponseVariant(name: "default", statusCode: HTTPStatusCode(code: 204), body: nil)
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
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/search",
                variants: [
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

    @Test("Query matching decodes form plus and percent escapes")
    func requestMatchDecodesQuery() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get,
                path: "/search",
                variants: [
                    ResponseVariant(name: "default", statusCode: .ok, body: Data("default".utf8)),
                    ResponseVariant(
                        name: "matched",
                        statusCode: .ok,
                        body: Data("matched".utf8),
                        requestMatch: RequestMatch(query: ["term name": "swift server"])
                    ),
                ]
            ))
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/search?term%20name=swift+server") { res async in
            #expect(res.body.string == "matched")
        }
    }

    @Test("Variant with requestMatch on headers is selected when header matches")
    func requestMatchHeaders() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/data",
                variants: [
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

    @Test("Path parameters match via Vapor native routing")
    func pathParameterMatching() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/users/{id}"))

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

    @Test("GraphQL anonymous document resolves matching endpoint")
    func graphqlAnonymousDocumentResolution() async throws {
        let store = InMemoryMockStore()
        await store.register(
            Endpoint(
                key: EndpointKey(method: .post, path: "/graphql"),
                authRequirement: .none,
                variants: [
                    ResponseVariant(
                        name: "current-user", statusCode: .ok,
                        body: Data(#"{"data":{"currentUser":{"id":"123"}}}"#.utf8))
                ],
                operation: EndpointOperation(
                    type: .query,
                    document: "query { currentUser { id name } }"
                )
            ))
        await store.register(
            Endpoint(
                key: EndpointKey(method: .post, path: "/graphql"),
                authRequirement: .none,
                variants: [
                    ResponseVariant(
                        name: "current-account", statusCode: .ok,
                        body: Data(#"{"data":{"currentAccount":{"id":"999"}}}"#.utf8))
                ],
                operation: EndpointOperation(
                    type: .query,
                    document: "query { currentAccount { id } }"
                )
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .POST,
            "/graphql",
            headers: ["Content-Type": "application/json"],
            body: ByteBuffer(string: #"{"query":"query { currentUser { id name } }"}"#)
        ) { res async in
            #expect(res.status == .ok)
            let body = String(buffer: res.body)
            #expect(body.contains("currentUser"))
            #expect(!body.contains("currentAccount"))
        }
    }

    @Test("Cookie validation returns specific error code")
    func cookieValidation() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get,
                path: "/needs-cookie",
                cookieRules: [RuleMatcher(name: "session_id", matchType: .notEmpty)]
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/needs-cookie") { res async in
            #expect(res.status == .badRequest)
            let body = String(buffer: res.body)
            #expect(body.contains("cookie_value_mismatch"))
        }
    }

    @Test("Packet loss simulation returns 503")
    func packetLossSimulation() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get,
                path: "/lossy",
                network: NetworkBehavior(latencyMs: 0, jitterMs: 0, packetLossPercent: 100)
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/lossy") { res async in
            #expect(res.status == .serviceUnavailable)
            let body = String(buffer: res.body)
            #expect(body.contains("simulated_packet_loss"))
        }
    }

    // MARK: - Call Count

    @Test("call_count sequences variants across calls, falling back once exhausted")
    func callCountSequencing() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/jobs/1",
                variants: [
                    ResponseVariant(
                        name: "pending", isDefault: true, statusCode: .ok, body: Data(#"{"status":"pending"}"#.utf8),
                        callCount: 1),
                    ResponseVariant(
                        name: "done", statusCode: .ok, body: Data(#"{"status":"done"}"#.utf8), callCount: 2),
                ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/jobs/1") { res async in
            #expect(res.status == .ok)
            #expect(String(buffer: res.body).contains("pending"))
        }
        try await app.testing().test(.GET, "/jobs/1") { res async in
            #expect(res.status == .ok)
            #expect(String(buffer: res.body).contains("done"))
        }
        // call_count exhausted, no strict mode: falls back to the default variant.
        try await app.testing().test(.GET, "/jobs/1") { res async in
            #expect(res.status == .ok)
            #expect(String(buffer: res.body).contains("pending"))
        }
    }

    @Test("strict_call_count rejects a call with no exact call_count match")
    func strictCallCountRejectsDuplicate() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/once",
                variants: [
                    ResponseVariant(name: "first", statusCode: .ok, body: Data("{}".utf8), callCount: 1)
                ],
                strictCallCount: true
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/once") { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/once") { res async in
            #expect(res.status == .conflict)
            let body = String(buffer: res.body)
            #expect(body.contains("call_count_exceeded"))
        }
    }

    @Test("strict_call_count counter advances even on a rejected call")
    func strictCallCountCounterAdvancesOnRejection() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/twice",
                variants: [
                    ResponseVariant(name: "first", statusCode: .ok, body: Data("{}".utf8), callCount: 1),
                    ResponseVariant(name: "third", statusCode: .ok, body: Data(#"{"n":3}"#.utf8), callCount: 3),
                ],
                strictCallCount: true
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/twice") { res async in
            #expect(res.status == .ok)  // call #1
        }
        try await app.testing().test(.GET, "/twice") { res async in
            #expect(res.status == .conflict)  // call #2, rejected, but counter still advances
        }
        try await app.testing().test(.GET, "/twice") { res async in
            #expect(res.status == .ok)  // call #3, matches "third", not retried as #2
            #expect(String(buffer: res.body).contains("3"))
        }
    }

    @Test("Endpoints with no call_count anywhere behave identically across repeated calls")
    func callCountAbsentIsBackwardCompatible() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/pets",
                variants: [
                    ResponseVariant(name: "default", statusCode: .ok, body: Data(#"{"status":"ok"}"#.utf8))
                ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        for _ in 0..<3 {
            try await app.testing().test(.GET, "/pets") { res async in
                #expect(res.status == .ok)
                #expect(String(buffer: res.body).contains("ok"))
            }
        }
    }

    @Test("Admin can reset the call counter for an endpoint")
    func adminResetsCallCount() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/once",
                variants: [
                    ResponseVariant(name: "first", statusCode: .ok, body: Data("{}".utf8), callCount: 1)
                ],
                strictCallCount: true
            ))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/once") { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/once") { res async in
            #expect(res.status == .conflict)
        }
        try await app.testing().test(.DELETE, "/_admin/endpoints/GET/once/call-count") { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/once") { res async in
            #expect(res.status == .ok)
        }
    }

    @Test("Admin endpoint detail reflects current and configured call counts")
    func adminEndpointDetailReportsCallCounts() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/jobs/1",
                variants: [
                    ResponseVariant(name: "pending", statusCode: .ok, body: Data("{}".utf8), callCount: 1)
                ]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/jobs/1") { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/_admin/endpoints/GET/jobs/1") { res async in
            #expect(res.status == .ok)
            let body = String(buffer: res.body)
            #expect(body.contains(#""currentCallCount":1"#))
            #expect(body.contains(#""callCount":1"#))
        }
    }
}
