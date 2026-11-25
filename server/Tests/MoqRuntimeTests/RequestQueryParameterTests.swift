import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct RequestQueryParameterTests {
    private func makeApp(captureRequestQuery: Bool = true) async throws -> Application {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/v1/items"))
        return try await buildApp(store: store, captureRequestQuery: captureRequestQuery, redactHeaders: ["X-Signed"])
    }

    private func query(
        _ app: Application, _ query: RequestQuery, status: HTTPStatus = .ok
    ) async throws -> RequestQueryResult? {
        var result: RequestQueryResult?
        try await app.testing().test(
            .POST, "/_admin/requests/query", headers: ["Content-Type": "application/json"],
            body: ByteBuffer(data: try JSONEncoder().encode(query))
        ) { res async in
            #expect(res.status == status)
            if res.status == .ok { result = try? res.content.decode(RequestQueryResult.self) }
        }
        return result
    }

    private func call(_ app: Application, _ path: String) async throws {
        try await app.testing().test(.GET, path) { _ async in }
    }

    @Test("Captures parameters in order, decoded, with repeats and flags")
    func capturesInOrder() {
        let captured = CapturedQueryParameter.captureAll("status=done&tag=a&tag=b%20c&q=x+y&flag&=skipped")
        #expect(captured.map(\.name) == ["status", "tag", "tag", "q", "flag"])
        #expect(captured.map(\.value) == ["done", "a", "b c", "x y", ""])
        #expect(CapturedQueryParameter.captureAll(nil).isEmpty)
        #expect(CapturedQueryParameter.captureAll("").isEmpty)
    }

    @Test("Credential-like parameters keep only a fingerprint")
    func redactsCredentials() {
        let captured = CapturedQueryParameter.captureAll(
            "api_key=secret-1&access_token=t&x-signed=s&page=2", redactNames: ["x-signed"])
        #expect(captured.filter(\.redacted).map(\.name) == ["api_key", "access_token", "x-signed"])
        #expect(captured.allSatisfy { $0.redacted ? $0.value == nil : $0.value == "2" })
        #expect(captured[0].fingerprint == CapturedHeader.fingerprint(of: "secret-1"))
    }

    @Test("A query predicate tells the filter the app sent from another legal one")
    func matchesByValue() async throws {
        let app = try await makeApp()
        try await call(app, "/v1/items?status=open&page=1")

        let done = try #require(
            try await query(
                app, RequestQuery(endpoint: "GET /v1/items", query: [QueryPredicate(name: "status", equals: "done")])))
        #expect(!done.ok)
        #expect(done.nearMisses.first?.failed == ["query status = 'done'"])
        #expect(done.message.contains("GET /v1/items?status=open&page=1 → 200: failed query status = 'done'"))

        let open = try #require(
            try await query(
                app,
                RequestQuery(
                    endpoint: "GET /v1/items",
                    query: [
                        QueryPredicate(name: "status", equals: "open"), QueryPredicate(name: "page", present: true),
                        QueryPredicate(name: "Status", present: false),
                    ], times: 1)))
        #expect(open.ok)
        #expect(open.matched.first?.requestQuery?.map(\.name) == ["status", "page"])
        try await app.asyncShutdown()
    }

    @Test("A redacted parameter is matched by fingerprint and never echoed")
    func matchesCredentialByFingerprint() async throws {
        let app = try await makeApp()
        try await call(app, "/v1/items?token=abc123")
        let result = try #require(
            try await query(
                app, RequestQuery(query: [QueryPredicate(name: "token", fingerprintOf: "abc123")], times: 1)))
        #expect(result.ok)
        #expect(result.matched.first?.requestQuery?.first?.value == nil)

        let wrong = try #require(
            try await query(app, RequestQuery(query: [QueryPredicate(name: "token", fingerprintOf: "other")])))
        #expect(!wrong.ok)
        #expect(!wrong.message.contains("abc123"))
        #expect(wrong.message.contains("?token=<redacted>"))
        try await app.asyncShutdown()
    }

    @Test("Query predicates fail loudly when they cannot be answered")
    func invalidPredicates() async throws {
        let off = try await makeApp(captureRequestQuery: false)
        _ = try await query(off, RequestQuery(query: [QueryPredicate(name: "status")]), status: .conflict)
        try await off.asyncShutdown()

        let app = try await makeApp()
        _ = try await query(app, RequestQuery(query: [QueryPredicate(name: "")]), status: .badRequest)
        _ = try await query(
            app, RequestQuery(query: [QueryPredicate(name: "api_key", equals: "x")]), status: .badRequest)
        try await app.asyncShutdown()
    }

    @Test("Rows carry no requestQuery unless capture is on")
    func offByDefault() async throws {
        let app = try await makeApp(captureRequestQuery: false)
        try await call(app, "/v1/items?status=open")
        try await app.testing().test(.GET, "/_admin/requests") { res async in
            let rows = try? res.content.decode([RequestTrace].self)
            #expect(rows?.count == 1)
            #expect(rows?.first?.requestQuery == nil)
        }
        try await app.asyncShutdown()
    }
}
