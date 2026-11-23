import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct RequestHeaderCaptureTests {
    private let token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEifQ."

    @Test("Headers are not recorded unless capture is enabled")
    func offByDefault() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/me"))
        let app = try await buildApp(store: store)
        try await app.testing().test(.GET, "/me", headers: ["Accept": "application/json"]) { _ async in }
        #expect(await store.recentRequests().first?.requestHeaders == nil)
        try await app.asyncShutdown()
    }

    @Test("Credential headers keep scheme, fingerprint and cookie names, never the value")
    func redactsCredentials() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/me"))
        let app = try await buildApp(store: store, captureRequestHeaders: true)
        try await app.testing().test(
            .GET, "/me",
            headers: [
                "Authorization": "Bearer \(token)", "Cookie": "sid=secret; theme=dark", "X-Api-Key": "k-123",
                "Accept": "application/json",
            ]
        ) { _ async in }

        let headers = try #require(await store.recentRequests().first?.requestHeaders)
        let byName = Dictionary(headers.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })

        let auth = try #require(byName["authorization"])
        #expect(auth.value == nil)
        #expect(auth.redacted)
        #expect(auth.scheme == "Bearer")
        #expect(auth.fingerprint == CapturedHeader.fingerprint(of: token))
        #expect(auth.fingerprint?.count == 16)

        let cookie = try #require(byName["cookie"])
        #expect(cookie.value == nil)
        #expect(cookie.cookieNames == ["sid", "theme"])
        #expect(cookie.fingerprint == nil)

        let apiKey = try #require(byName["x-api-key"])
        #expect(apiKey.scheme == nil)
        #expect(apiKey.fingerprint == CapturedHeader.fingerprint(of: "k-123"))

        #expect(byName["accept"]?.value == "application/json")
        #expect(byName["accept"]?.redacted == false)

        let raw = String(decoding: try JSONEncoder().encode(headers), as: UTF8.self)
        #expect(!raw.contains(token))
        #expect(!raw.contains("secret"))
        #expect(!raw.contains("k-123"))
        try await app.asyncShutdown()
    }

    @Test("Custom auth headers are redacted by keyword, JWT value, or --redact-header")
    func redactsCustomAuthHeaders() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/me"))
        let app = try await buildApp(store: store, captureRequestHeaders: true, redactHeaders: ["X-App-Device"])
        let id = try await store.createRuntimeSession()
        try await app.testing().test(
            .GET, "/me",
            headers: [
                "X-Firebase-AppCheck": "appcheck-raw", "X-Correlation": token, "X-App-Device": "device-raw",
                "X-Mock-Session": id, "Accept": "application/json",
            ]
        ) { _ async in }

        let headers = try #require(await store.runtimeSession(id)?.recentRequests().first?.requestHeaders)
        let byName = Dictionary(headers.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
        #expect(byName["x-firebase-appcheck"]?.redacted == true)
        #expect(byName["x-firebase-appcheck"]?.fingerprint == CapturedHeader.fingerprint(of: "appcheck-raw"))
        #expect(byName["x-correlation"]?.redacted == true)
        #expect(byName["x-app-device"]?.redacted == true)
        #expect(byName["x-mock-session"]?.value == id)
        #expect(byName["accept"]?.value == "application/json")

        let raw = String(decoding: try JSONEncoder().encode(headers), as: UTF8.self)
        #expect(!raw.contains("appcheck-raw"))
        #expect(!raw.contains("device-raw"))
        #expect(!raw.contains(token))
        try await app.asyncShutdown()
    }

    @Test("A mismatched custom credential header is not echoed in the rejection")
    func mismatchHidesCustomCredential() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/me",
                headerRules: [RuleMatcher(name: "X-Refresh-Token", match: "expected", matchType: .equalTo)]))
        let app = try await buildApp(store: store)
        try await app.testing().test(.GET, "/me", headers: ["X-Refresh-Token": "refresh-raw"]) { res async in
            #expect(res.status == .badRequest)
            #expect(!res.body.string.contains("refresh-raw"))
        }
        #expect(await store.recentRequests().first?.rejection?.message.contains("got a redacted value") == true)
        try await app.asyncShutdown()
    }

    @Test("Long non-credential values are cut and marked truncated")
    func truncatesLongValues() {
        let header = CapturedHeader.capture(name: "X-Trace", value: String(repeating: "a", count: 300))
        #expect(header.value?.count == CapturedHeader.valueLimit)
        #expect(header.truncated)
    }

    @Test("A request_rules failure records the failing rule; served and mocked-4xx rows have none")
    func recordsRuleRejection() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/me",
                headerRules: [RuleMatcher(name: "Authorization", required: true, matchType: .require)]))
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/gone",
                variants: [
                    ResponseVariant(name: "gone", statusCode: HTTPStatusCode(code: 410), headers: [], body: Data())
                ]))
        let app = try await buildApp(store: store)

        try await app.testing().test(.GET, "/me") { res async in #expect(res.status == .badRequest) }
        let rejected = try #require(await store.recentRequests().first)
        #expect(
            rejected.rejection
                == RequestRejection(
                    kind: .requestRule, code: "missing_required_header",
                    message: "Header 'Authorization' is required"))

        try await app.testing().test(.GET, "/me", headers: ["Authorization": "Bearer x"]) { _ async in }
        #expect(await store.recentRequests().first?.rejection == nil)

        try await app.testing().test(.GET, "/gone") { res async in #expect(res.status == .gone) }
        #expect(await store.recentRequests().first?.rejection == nil)
        try await app.asyncShutdown()
    }

    @Test("A mismatched credential header is not echoed in the rejection")
    func mismatchHidesCredential() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/content",
                headerRules: [RuleMatcher(name: "Authorization", required: false, matchType: .isEmpty)]))
        let app = try await buildApp(store: store)

        try await app.testing().test(.GET, "/content", headers: ["Authorization": "Bearer \(token)"]) { res async in
            #expect(res.status == .badRequest)
            #expect(!res.body.string.contains(token))
        }
        let rejection = try #require(await store.recentRequests().first?.rejection)
        #expect(rejection.kind == .requestRule)
        #expect(rejection.message == "Header 'Authorization' must be empty, got a redacted value")
        try await app.asyncShutdown()
    }

    @Test("A request with an unknown session is recorded in global history as a session rejection")
    func recordsUnknownSession() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/me"))
        let app = try await buildApp(store: store)

        try await app.testing().test(.GET, "/me", headers: ["X-Mock-Session": "stale-id"]) { res async in
            #expect(res.status == .notFound)
        }
        try await app.testing().test(.GET, "/nowhere", headers: ["X-Mock-Session": "stale-id"]) { res async in
            #expect(res.status == .notFound)
        }
        let rows = await store.recentRequests()
        #expect(rows.map(\.endpoint) == [nil, "GET /me"])
        #expect(rows.allSatisfy { $0.rejection?.kind == .session && $0.rejection?.code == "unknown_session" })
        #expect(rows.first?.rejection?.message.contains("'stale-id'") == true)
        try await app.asyncShutdown()
    }

    @Test("Auth failures and missing sessions record their rejection kind")
    func recordsAuthAndSessionRejections() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/secure", auth: .bearer))
        let app = try await buildApp(store: store, requireSession: true)

        try await app.testing().test(.GET, "/secure") { res async in #expect(res.status == .preconditionRequired) }
        #expect(
            await store.recentRequests().first?.rejection
                == RequestRejection(kind: .session, code: "session_required", message: "Missing X-Mock-Session header"))

        let id = try await store.createRuntimeSession()
        try await app.testing().test(.GET, "/secure", headers: ["X-Mock-Session": id]) { res async in
            #expect(res.status == .unauthorized)
        }
        let row = try #require(await store.runtimeSession(id)?.recentRequests().first)
        #expect(row.rejection?.kind == .auth)
        #expect(row.rejection?.code == "unauthorized")
        try await app.asyncShutdown()
    }
}
