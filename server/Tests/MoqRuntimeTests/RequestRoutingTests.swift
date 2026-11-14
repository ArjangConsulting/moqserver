import Foundation
import Testing
import Vapor
import VaporTesting

@testable import MoqCore
@testable import MoqRuntime

@Suite("Request routing: body limits, methods, CORS, body rules")
struct RequestRoutingTests {
    private func store(_ endpoints: [Endpoint]) async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        for endpoint in endpoints { await store.register(endpoint) }
        return store
    }

    private func errorCode(_ res: TestingHTTPResponse) -> String? {
        try? JSONDecoder().decode(ErrorResponse.self, from: Data(buffer: res.body)).code
    }

    // MARK: - Body size

    @Test("A request body far above Vapor's 16 KB default reaches the handler")
    func largeBodyAccepted() async throws {
        let uploadMatch = RequestMatch(bodyContains: "needle")
        let app = try await buildApp(
            store: await store([
                makeTestEndpoint(
                    method: .post, path: "/upload",
                    variants: [
                        ResponseVariant(
                            name: "matched", statusCode: HTTPStatusCode(code: 201), requestMatch: uploadMatch),
                        ResponseVariant(name: "fallback", isDefault: true, statusCode: .ok),
                    ])
            ]))
        let body = String(repeating: "a", count: 200 * 1024) + "needle"
        try await app.testing(method: .running(port: 0)).test(
            .POST, "/upload", headers: ["Content-Type": "text/plain"], body: ByteBuffer(string: body)
        ) { res async in
            #expect(res.status == .created)
        }
        try await app.asyncShutdown()
    }

    @Test("A body over --max-body-size gets 413 payload_too_large")
    func oversizedBodyRejected() async throws {
        let app = try await buildApp(
            store: await store([makeTestEndpoint(method: .post, path: "/upload")]), maxRequestBodySize: 1024)
        try await app.testing(method: .running(port: 0)).test(
            .POST, "/upload", headers: ["Content-Type": "text/plain"],
            body: ByteBuffer(string: String(repeating: "a", count: 4096))
        ) { res async in
            #expect(res.status == .payloadTooLarge)
            #expect(errorCode(res) == "payload_too_large")
        }
        try await app.asyncShutdown()
    }

    // MARK: - Methods

    @Test("A wrong method on a known path is 405 with Allow, not 404")
    func methodNotAllowed() async throws {
        let store = await store([
            makeTestEndpoint(method: .get, path: "/users/{id}"),
            makeTestEndpoint(method: .delete, path: "/users/{userId}"),
        ])
        let app = try await buildApp(store: store)
        try await app.testing().test(.POST, "/users/42") { res async in
            #expect(res.status == .methodNotAllowed)
            #expect(res.headers.first(name: "Allow") == "DELETE, GET, HEAD, OPTIONS")
            #expect(errorCode(res) == "method_not_allowed")
        }
        try await app.testing().test(.POST, "/nowhere") { res async in
            #expect(res.status == .notFound)
        }
        #expect(await store.recentRequests().first?.status == 404)
        #expect(await store.recentRequests().dropFirst().first?.status == 405)
        try await app.asyncShutdown()
    }

    @Test("OPTIONS on a known path answers 204 with Allow")
    func optionsAllowed() async throws {
        let app = try await buildApp(store: await store([makeTestEndpoint(method: .post, path: "/items")]))
        try await app.testing().test(.OPTIONS, "/items") { res async in
            #expect(res.status == .noContent)
            #expect(res.headers.first(name: "Allow") == "OPTIONS, POST")
        }
        try await app.asyncShutdown()
    }

    @Test("HEAD is served by the GET endpoint, without a body")
    func headUsesGet() async throws {
        let store = await store([makeTestEndpoint(method: .get, path: "/users/{id}")])
        let app = try await buildApp(store: store)
        try await app.testing(method: .running(port: 0)).test(.HEAD, "/users/7") { res async in
            #expect(res.status == .ok)
            #expect(res.body.readableBytes == 0)
            #expect(res.headers.first(name: .contentType) == "application/json")
        }
        #expect(await store.currentCallCount(for: "GET /users/{id}") == 1)
        try await app.asyncShutdown()
    }

    // MARK: - CORS

    @Test("--cors answers preflights and reflects the requested headers")
    func corsPreflight() async throws {
        let app = try await buildApp(store: await store([makeTestEndpoint(method: .get, path: "/users")]), cors: true)
        let headers: HTTPHeaders = [
            "Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "x-mock-variant, x-api-key",
        ]
        try await app.testing().test(.OPTIONS, "/users", headers: headers) { res async in
            #expect(res.status == .noContent)
            #expect(res.headers.first(name: .accessControlAllowOrigin) == "http://localhost:3000")
            #expect(res.headers.first(name: .accessControlAllowHeaders) == "x-mock-variant, x-api-key")
            #expect(res.headers.first(name: .accessControlAllowMethods)?.contains("PATCH") == true)
        }
        try await app.testing().test(.GET, "/missing", headers: ["Origin": "http://localhost:3000"]) { res async in
            #expect(res.status == .notFound)
            #expect(res.headers.first(name: .accessControlAllowOrigin) == "http://localhost:3000")
        }
        try await app.asyncShutdown()
    }

    @Test("Without --cors no CORS headers are added")
    func corsOffByDefault() async throws {
        let app = try await buildApp(store: await store([makeTestEndpoint(method: .get, path: "/users")]))
        try await app.testing().test(.GET, "/users", headers: ["Origin": "http://localhost:3000"]) { res async in
            #expect(res.status == .ok)
            #expect(res.headers.first(name: .accessControlAllowOrigin) == nil)
        }
        try await app.asyncShutdown()
    }

    // MARK: - Body rules

    @Test("requires_body rejects an empty body with missing_request_body")
    func requiresBody() async throws {
        let app = try await buildApp(
            store: await store([makeTestEndpoint(method: .post, path: "/orders", requiresBody: true)]))
        try await app.testing().test(.POST, "/orders") { res async in
            #expect(res.status == .badRequest)
            #expect(errorCode(res) == "missing_request_body")
        }
        try await app.testing().test(
            .POST, "/orders", headers: ["Content-Type": "application/json"], body: ByteBuffer(string: "{}")
        ) { res async in
            #expect(res.status == .ok)
        }
        try await app.asyncShutdown()
    }

    @Test("accepted_content_types rejects other media types with unsupported_content_type")
    func acceptedContentTypes() async throws {
        let app = try await buildApp(
            store: await store([
                makeTestEndpoint(method: .post, path: "/avatar", acceptedContentTypes: ["image/*", "application/json"])
            ]))
        try await app.testing().test(
            .POST, "/avatar", headers: ["Content-Type": "text/plain"], body: ByteBuffer(string: "hi")
        ) { res async in
            #expect(res.status == .unsupportedMediaType)
            #expect(errorCode(res) == "unsupported_content_type")
        }
        try await app.testing().test(
            .POST, "/avatar", headers: ["Content-Type": "image/png"], body: ByteBuffer(bytes: [0x89, 0x50])
        ) { res async in
            #expect(res.status == .ok)
        }
        try await app.asyncShutdown()
    }
}
