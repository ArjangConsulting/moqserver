import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqAddonKit
@testable import MoqAddons
@testable import MoqCore
@testable import MoqRuntime

/// An unsigned JWS compact token, the shape the Firebase Auth emulator issues.
private func unsignedToken(_ claims: [String: Any]) throws -> String {
    func encode(_ object: [String: Any]) throws -> String {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    return try "\(encode(["alg": "none", "typ": "JWT"])).\(encode(claims))."
}

/// A test-only add-on exercising H1: echoes the relative path, a query value, and the body.
private struct EchoAddon: MoqAddon {
    static let id = "echo"
    static func validateConfig(_ config: AnyCodableValue) -> [AddonDiagnostic] { [] }
    init(config: AnyCodableValue, environment: AddonEnvironment) throws {}

    var routes: [AddonRoute] {
        [
            AddonRoute(method: "POST", path: ["echo", ":name"]) { request in
                .json(
                    .object([
                        "path": .string(request.path),
                        "q": .string(request.query["q"] ?? ""),
                        "body": .string(request.body.map { String(decoding: $0, as: UTF8.self) } ?? ""),
                    ]), status: 201)
            }
        ]
    }
}

struct AddonRuntimeTests {
    private let premiumSpec: AnyCodableValue = .object(["claims": .object(["premium": .bool(true)])])

    private func store() async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        await store.register(
            Endpoint(
                key: EndpointKey(method: .get, path: "/user-access"), authRequirement: .none,
                variants: [
                    ResponseVariant(name: "free", isDefault: true, statusCode: .ok, body: Data(#"{"tier":"free"}"#.utf8)),
                    ResponseVariant(
                        name: "premium", statusCode: .ok, body: Data(#"{"tier":"premium"}"#.utf8),
                        requestMatch: RequestMatch(addons: ["jwt-claims": premiumSpec])),
                ]))
        return store
    }

    private func jwtAddons() throws -> ActiveAddons {
        try AddonCatalog.builtIn.activate(
            ["jwt-claims": .object(["verify_signature": .bool(false)])], environment: AddonEnvironment())
    }

    private func tier(_ res: XCTHTTPResponse) -> String? {
        (try? JSONSerialization.jsonObject(with: Data(buffer: res.body)) as? [String: String])?["tier"]
    }

    @Test("A claim in the bearer token selects the matching variant")
    func claimSelectsVariant() async throws {
        let app = try await buildApp(store: await store(), addons: try jwtAddons())
        let premium = try unsignedToken(["sub": "u-premium", "premium": true])
        let free = try unsignedToken(["sub": "u-free", "premium": false])

        try await app.testing().test(
            .GET, "/user-access", headers: ["Authorization": "Bearer \(premium)"],
            afterResponse: { res async in #expect(tier(res) == "premium") })
        try await app.testing().test(
            .GET, "/user-access", headers: ["Authorization": "Bearer \(free)"],
            afterResponse: { res async in #expect(tier(res) == "free") })
        try await app.testing().test(
            .GET, "/user-access", afterResponse: { res async in #expect(tier(res) == "free") })
        try await app.asyncShutdown()
    }

    @Test("Without the add-on active, an add-on predicate never matches")
    func inactiveAddonNeverMatches() async throws {
        let app = try await buildApp(store: await store())
        let premium = try unsignedToken(["sub": "u", "premium": true])
        try await app.testing().test(
            .GET, "/user-access", headers: ["Authorization": "Bearer \(premium)"],
            afterResponse: { res async in #expect(tier(res) == "free") })
        try await app.asyncShutdown()
    }

    @Test("Parallel sessions see only their own token's claims, and history records sub")
    func sessionIsolation() async throws {
        let store = await store()
        let app = try await buildApp(store: store, addons: try jwtAddons())
        let sessionA = try await store.createRuntimeSession()
        let sessionB = try await store.createRuntimeSession()
        let tokenA = try unsignedToken(["sub": "alice", "premium": true])
        let tokenB = try unsignedToken(["sub": "bob", "premium": false])

        try await withThrowingTaskGroup(of: Void.self) { group in
            for (session, token, expected) in [(sessionA, tokenA, "premium"), (sessionB, tokenB, "free")] {
                group.addTask {
                    for _ in 0..<5 {
                        try await app.testing().test(
                            .GET, "/user-access",
                            headers: ["Authorization": "Bearer \(token)", "X-Mock-Session": session],
                            afterResponse: { res async in #expect(tier(res) == expected) })
                    }
                }
            }
            try await group.waitForAll()
        }

        let historyA = try await #require(await store.runtimeSession(sessionA)).recentRequests()
        let historyB = try await #require(await store.runtimeSession(sessionB)).recentRequests()
        #expect(historyA.count == 5 && historyB.count == 5)
        #expect(historyA.allSatisfy { $0.addons == ["jwt-claims": ["sub": "alice"]] && $0.variant == "premium" })
        #expect(historyB.allSatisfy { $0.addons == ["jwt-claims": ["sub": "bob"]] && $0.variant == "free" })
        #expect(await store.recentRequests().isEmpty)
        try await app.asyncShutdown()
    }

    @Test("Unmatched requests are annotated too, and rows without facts omit the field")
    func notFoundAnnotated() async throws {
        let store = await store()
        let app = try await buildApp(store: store, addons: try jwtAddons())
        let token = try unsignedToken(["sub": "carol"])
        try await app.testing().test(
            .GET, "/missing", headers: ["Authorization": "Bearer \(token)"],
            afterResponse: { res async in #expect(res.status == .notFound) })
        try await app.testing().test(.GET, "/user-access", afterResponse: { _ async in })
        let history = await store.recentRequests()
        #expect(history.map(\.addons) == [nil, ["jwt-claims": ["sub": "carol"]]])

        let encoded = try JSONEncoder().encode(history[0])
        #expect(!String(decoding: encoded, as: UTF8.self).contains("addons"))
        try await app.asyncShutdown()
    }

    @Test("Add-on routes are mounted under /_addons/<id>/")
    func addonRoutes() async throws {
        let app = try await buildApp(store: await store(), addons: ActiveAddons([try EchoAddon(config: .null, environment: AddonEnvironment())]))
        try await app.testing().test(
            .POST, "/_addons/echo/echo/hello?q=1",
            beforeRequest: { req async throws in req.body = ByteBuffer(string: "payload") },
            afterResponse: { res async throws in
                #expect(res.status == .created)
                let json = try JSONSerialization.jsonObject(with: Data(buffer: res.body)) as? [String: String]
                #expect(json == ["path": "/echo/hello", "q": "1", "body": "payload"])
            })
        try await app.testing().test(
            .GET, "/_addons/echo/echo/hello", afterResponse: { res async in #expect(res.status == .notFound) })
        try await app.asyncShutdown()
    }
}
