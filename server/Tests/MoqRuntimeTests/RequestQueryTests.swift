import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqAddonKit
@testable import MoqAddons
@testable import MoqCore
@testable import MoqRuntime

struct RequestQueryTests {
    private func token(_ claims: [String: Any]) throws -> String {
        func encode(_ object: [String: Any]) throws -> String {
            try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return try "\(encode(["alg": "none", "typ": "JWT"])).\(encode(claims))."
    }

    private func makeApp(
        captureRequestHeaders: Bool = true, requireSession: Bool = false
    ) async throws -> (Application, InMemoryMockStore) {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/v1/me"))
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/content/{id}",
                headerRules: [RuleMatcher(name: "Authorization", required: false, matchType: .isEmpty)]))
        let jwt = try JWTClaimsAddon(
            config: .object([
                "verify_signature": .bool(false), "trace_claims": .array([.string("sub"), .string("iat")]),
            ]),
            environment: AddonEnvironment())
        let app = try await buildApp(
            store: store, addons: ActiveAddons([jwt]), requireSession: requireSession,
            captureRequestHeaders: captureRequestHeaders)
        return (app, store)
    }

    private func query(
        _ app: Application, _ query: RequestQuery, session: String? = nil, status: HTTPStatus = .ok
    ) async throws -> RequestQueryResult? {
        var result: RequestQueryResult?
        var headers: HTTPHeaders = ["Content-Type": "application/json"]
        if let session { headers.add(name: "X-Mock-Session", value: session) }
        try await app.testing().test(
            .POST, "/_admin/requests/query", headers: headers, body: ByteBuffer(data: try JSONEncoder().encode(query))
        ) { res async in
            #expect(res.status == status)
            if res.status == .ok { result = try? res.content.decode(RequestQueryResult.self) }
        }
        return result
    }

    private func call(_ app: Application, _ path: String, headers: HTTPHeaders = [:]) async throws {
        try await app.testing().test(.GET, path, headers: headers) { _ async in }
    }

    @Test("Matches a Bearer call by sub and returns retries oldest first with their claims and fingerprints")
    func retryAfterRefresh() async throws {
        let (app, _) = try await makeApp()
        let first = try token(["sub": "u1", "iat": 100])
        let second = try token(["sub": "u1", "iat": 200])
        try await call(app, "/v1/me", headers: ["Authorization": "Bearer \(first)"])
        try await call(app, "/v1/me", headers: ["Authorization": "Bearer \(second)"])

        let result = try #require(
            try await query(
                app,
                RequestQuery(
                    endpoint: "get /v1/me", headers: [HeaderPredicate(name: "Authorization", scheme: "bearer")],
                    claims: ["sub": "u1"], times: 2)))
        #expect(result.ok)
        #expect(result.matched.map { $0.addons?["jwt-claims"]?["iat"] } == ["100", "200"])
        let fingerprints = result.matched.compactMap {
            $0.requestHeaders?.first { $0.name == "authorization" }?.fingerprint
        }
        #expect(fingerprints == [CapturedHeader.fingerprint(of: first), CapturedHeader.fingerprint(of: second)])

        let one = try #require(
            try await query(
                app,
                RequestQuery(
                    endpoint: "GET /v1/me",
                    headers: [HeaderPredicate(name: "authorization", fingerprintOf: second)], times: 1)))
        #expect(one.ok)
        try await app.asyncShutdown()
    }

    @Test("A failed assertion explains near misses")
    func nearMisses() async throws {
        let (app, _) = try await makeApp()
        try await call(app, "/v1/me", headers: ["Authorization": "Basic dTpw"])

        let result = try #require(
            try await query(
                app,
                RequestQuery(
                    endpoint: "GET /v1/me", headers: [HeaderPredicate(name: "Authorization", scheme: "Bearer")],
                    claims: ["sub": "u1"], times: 1)))
        #expect(!result.ok)
        #expect(result.count == 0)
        #expect(result.nearMisses.first?.failed == ["authorization scheme Bearer", "claim sub=u1"])
        #expect(result.message.contains("Expected exactly 1 request(s) to GET /v1/me"))
        #expect(result.message.contains("GET /v1/me → 200: failed authorization scheme Bearer"))
        try await app.asyncShutdown()
    }

    @Test("Absent header, rejected requests, and a since mark")
    func absentRejectedAndSince() async throws {
        let (app, _) = try await makeApp()
        try await call(app, "/content/1")
        let absent = try #require(
            try await query(
                app,
                RequestQuery(
                    endpoint: "GET /content/{id}", headers: [HeaderPredicate(name: "Authorization", present: false)],
                    times: 1)))
        #expect(absent.ok)

        var mark = 0.0
        try await app.testing().test(.POST, "/_admin/requests/mark") { res async in
            mark = (try? res.content.decode([String: Double].self))?["timestamp"] ?? 0
        }
        try await call(app, "/content/2", headers: ["Authorization": "Bearer leaked"])

        let rejected = try #require(try await query(app, RequestQuery(rejected: true, times: 0)))
        #expect(!rejected.ok)
        #expect(rejected.message.contains("GET /content/2 → 400 (request_rule header_value_mismatch"))

        let afterMark = try #require(
            try await query(
                app, RequestQuery(since: mark, headers: [HeaderPredicate(name: "Authorization")], times: 0)))
        #expect(!afterMark.ok)
        #expect(afterMark.matched.map(\.path) == ["/content/2"])
        try await app.asyncShutdown()
    }

    @Test("Predicates that cannot be answered fail instead of passing")
    func invalidQueries() async throws {
        let (app, _) = try await makeApp(captureRequestHeaders: false)
        _ = try await query(
            app, RequestQuery(headers: [HeaderPredicate(name: "Authorization", present: false)]), status: .conflict)
        _ = try await query(
            app, RequestQuery(headers: [HeaderPredicate(name: "Authorization", equals: "Bearer x")]),
            status: .badRequest)
        _ = try await query(app, RequestQuery(times: -1), status: .badRequest)
        _ = try await query(app, RequestQuery(scope: .sessionless), status: .badRequest)
        try await app.asyncShutdown()
    }

    @Test("An endpoint that differs from a loaded key by a trailing slash resolves to it")
    func trailingSlashResolves() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/v1/user/profile/"))
        let app = try await buildApp(store: store)
        try await call(app, "/v1/user/profile/")

        let result = try #require(try await query(app, RequestQuery(endpoint: "get /v1/user/profile", times: 1)))
        #expect(result.ok)
        #expect(result.message.contains("GET /v1/user/profile/"))
        try await app.asyncShutdown()
    }

    @Test("An unknown endpoint, method or relative path fails instead of passing a times: 0 check")
    func unknownFiltersFail() async throws {
        let (app, _) = try await makeApp()
        var reason = ""
        try await app.testing().test(
            .POST, "/_admin/requests/query", headers: ["Content-Type": "application/json"],
            body: ByteBuffer(string: #"{"endpoint":"GET /v1/mee","times":0}"#)
        ) { res async in
            #expect(res.status == .badRequest)
            reason = res.body.string.replacingOccurrences(of: "\\/", with: "/")
        }
        #expect(reason.contains("Did you mean 'GET /v1/me'?"))

        _ = try await query(
            app, RequestQuery(endpoint: "GET /totally/elsewhere/entirely", times: 0), status: .badRequest)
        _ = try await query(app, RequestQuery(endpoint: "/v1/me", times: 0), status: .badRequest)
        _ = try await query(app, RequestQuery(endpoint: "POST /graphql#GetUser"), status: .badRequest)
        _ = try await query(app, RequestQuery(method: "GTE", times: 0), status: .badRequest)
        _ = try await query(app, RequestQuery(path: "v1/me", times: 0), status: .badRequest)
        try await app.asyncShutdown()
    }

    @Test("Session-less requests are attributed to a test by its token claims")
    func sessionless() async throws {
        let (app, store) = try await makeApp(requireSession: true)
        try await call(app, "/v1/me")
        let mine = try await store.createRuntimeSession()
        try await call(app, "/v1/me", headers: ["Authorization": "Bearer \(try token(["sub": "me"]))"])
        try await call(app, "/v1/me", headers: ["Authorization": "Bearer \(try token(["sub": "other"]))"])
        try await call(app, "/v1/me")
        try await call(
            app, "/v1/me", headers: ["Authorization": "Bearer \(try token(["sub": "me"]))", "X-Mock-Session": mine])

        let attributed = try #require(
            try await query(app, RequestQuery(claims: ["sub": "me"], times: 0, scope: .sessionless), session: mine))
        #expect(!attributed.ok)
        #expect(attributed.count == 1)
        #expect(attributed.matched.first?.rejection?.kind == .session)
        #expect(attributed.unattributed.count == 1)

        let everything = try #require(try await query(app, RequestQuery(times: 0, scope: .sessionless), session: mine))
        #expect(everything.count == 3)

        let withUnattributed = try #require(
            try await query(
                app, RequestQuery(claims: ["sub": "me"], times: 0, scope: .sessionless, includeUnattributed: true),
                session: mine))
        #expect(withUnattributed.count == 2)
        try await app.asyncShutdown()
    }
}
