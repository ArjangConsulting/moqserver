import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct RequireSessionTests {
    private func store() async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/users"))
        return store
    }

    @Test("--require-session rejects mock requests without X-Mock-Session and records them globally")
    func rejectsMissingSession() async throws {
        let store = await store()
        let app = try await buildApp(store: store, requireSession: true)
        for path in ["/users", "/unknown"] {
            try await app.testing().test(.GET, path) { res async in
                #expect(res.status == .preconditionRequired)
                #expect(res.body.string.contains("session_required"))
            }
        }
        let history = await store.recentRequests()
        #expect(history.map(\.reason) == ["missing session", "missing session"])
        #expect(await store.currentCallCount(for: "GET /users") == 0)
        try await app.asyncShutdown()
    }

    @Test("--require-session serves session requests and leaves admin and health routes open")
    func allowsSessionAndAdmin() async throws {
        let store = await store()
        let app = try await buildApp(store: store, requireSession: true)
        let id = try await store.createRuntimeSession()
        try await app.testing().test(.GET, "/users", headers: ["X-Mock-Session": id]) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/health") { res async in #expect(res.status == .ok) }
        try await app.testing().test(.GET, "/_admin/requests") { res async in #expect(res.status == .ok) }
        try await app.asyncShutdown()
    }

    @Test("Without the flag, requests without a session still use global state")
    func defaultUnchanged() async throws {
        let app = try await buildApp(store: await store())
        try await app.testing().test(.GET, "/users") { res async in #expect(res.status == .ok) }
        try await app.asyncShutdown()
    }
}

struct BaseURLTemplatingTests {
    private func app(body: String, stream: ResponseStream? = nil) async throws -> Application {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/profile",
                variants: [ResponseVariant(name: "default", body: Data(body.utf8), stream: stream)]))
        return try await buildApp(store: store)
    }

    @Test("{{baseURL}} resolves to the scheme and host the client used")
    func substitutesHost() async throws {
        let app = try await app(body: #"{"avatar":"{{baseURL}}/img/a.png","next":"{{baseURL}}/p/2"}"#)
        try await app.testing().test(.GET, "/profile", headers: ["Host": "10.0.2.2:8080"]) { res async in
            #expect(res.body.string == #"{"avatar":"http://10.0.2.2:8080/img/a.png","next":"http://10.0.2.2:8080/p/2"}"#)
        }
        try await app.testing().test(
            .GET, "/profile",
            headers: ["Host": "internal:8080", "X-Forwarded-Proto": "https", "X-Forwarded-Host": "mocks.example.test"]
        ) { res async in
            #expect(res.body.string.hasPrefix(#"{"avatar":"https://mocks.example.test/img"#))
        }
        try await app.asyncShutdown()
    }

    @Test("Bodies without the token are returned byte for byte")
    func untouchedWithoutToken() {
        let data = Data([0xFF, 0x00, 0x7B, 0x7B])
        #expect(MockHandler.substituteBaseURL(in: data, baseURL: "http://x") == data)
        #expect(
            MockHandler.substituteBaseURL(in: Data("{{baseURL}}{{baseURL}}".utf8), baseURL: "h")
                == Data("hh".utf8))
    }
}
