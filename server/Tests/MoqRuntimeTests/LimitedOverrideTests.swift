import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct LimitedOverrideTests {
    private func makeApp() async throws -> (Application, InMemoryMockStore) {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/v1/me",
                variants: [
                    ResponseVariant(name: "success", isDefault: true, statusCode: .ok, body: Data("{}".utf8)),
                    ResponseVariant(name: "unauthorized", statusCode: .unauthorized, body: Data("{}".utf8)),
                ],
                headerRules: [RuleMatcher(name: "X-Client", required: true, matchType: .require)]))
        return (try await buildApp(store: store), store)
    }

    private func setOverride(
        _ app: Application, times: Int?, session: String? = nil, status: HTTPStatus = .ok
    ) async throws {
        var headers: HTTPHeaders = ["Content-Type": "application/json"]
        if let session { headers.add(name: "X-Mock-Session", value: session) }
        let body = times.map { #"{"variant":"unauthorized","times":\#($0)}"# } ?? #"{"variant":"unauthorized"}"#
        try await app.testing().test(
            .PUT, "/_admin/endpoints/GET/v1/me/variant", headers: headers, body: ByteBuffer(string: body)
        ) { res async in
            #expect(res.status == status)
        }
    }

    private func statuses(_ app: Application, count: Int, session: String? = nil) async throws -> [UInt] {
        var headers: HTTPHeaders = ["X-Client": "app"]
        if let session { headers.add(name: "X-Mock-Session", value: session) }
        var codes: [UInt] = []
        for _ in 0..<count {
            try await app.testing().test(.GET, "/v1/me", headers: headers) { res async in
                codes.append(res.status.code)
            }
        }
        return codes
    }

    @Test("An override with times applies to that many requests in its session, then clears")
    func appliesThenClears() async throws {
        let (app, store) = try await makeApp()
        let session = try await store.createRuntimeSession()
        try await setOverride(app, times: 1, session: session)

        #expect(try await statuses(app, count: 1) == [200])
        #expect(try await statuses(app, count: 3, session: session) == [401, 200, 200])
        #expect(await store.runtimeSession(session)?.activeVariantOverride(for: "GET /v1/me") == nil)
        try await app.asyncShutdown()
    }

    @Test("Requests rejected before variant selection don't use up the override")
    func rejectedRequestsDontCount() async throws {
        let (app, store) = try await makeApp()
        try await setOverride(app, times: 2)
        try await app.testing().test(.GET, "/v1/me") { res async in #expect(res.status == .badRequest) }
        #expect(await store.remainingOverrideUses(for: "GET /v1/me") == 2)
        #expect(try await statuses(app, count: 3) == [401, 401, 200])
        try await app.asyncShutdown()
    }

    @Test("A permanent override replaces a limited one, and times must be positive")
    func permanentAndValidation() async throws {
        let (app, store) = try await makeApp()
        try await setOverride(app, times: 1)
        try await setOverride(app, times: nil)
        #expect(await store.remainingOverrideUses(for: "GET /v1/me") == nil)
        #expect(try await statuses(app, count: 2) == [401, 401])
        try await setOverride(app, times: 0, status: .badRequest)
        try await app.asyncShutdown()
    }
}
