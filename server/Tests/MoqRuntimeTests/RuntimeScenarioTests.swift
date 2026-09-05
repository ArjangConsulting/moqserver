import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct RuntimeScenarioTests {
    private func store() async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        await store.register(
            Endpoint(
                key: EndpointKey(method: .get, path: "/users"), authRequirement: .none,
                variants: [
                    ResponseVariant(name: "success", statusCode: .ok),
                    ResponseVariant(name: "error", statusCode: .internalServerError),
                ]))
        return store
    }

    @Test("scenario activation validates everything before replacing overrides and counters")
    func atomicActivation() async throws {
        let store = await store()
        await store.setVariantOverride(for: "GET /users", variant: "success")
        _ = await store.incrementCallCount(for: "GET /users")
        await #expect(throws: RuntimeScenarioError.self) {
            try await store.defineScenario(RuntimeScenario(name: "invalid", overrides: ["GET /missing": "error"]))
        }
        #expect(await store.currentCallCount(for: "GET /users") == 1)
        #expect(await store.activeVariantOverride(for: "GET /users") == "success")
        try await store.defineScenario(RuntimeScenario(name: "failure", overrides: ["GET /users": "error"]))
        try await store.activateScenario("failure")
        let context = await store.beginRequest(for: "GET /users")
        #expect(context.count == 1)
        #expect(context.override == "error")
        await store.resetRuntimeState()
        #expect(await store.activeVariantOverride(for: "GET /users") == nil)
        #expect(await store.currentCallCount(for: "GET /users") == 0)
    }

    @Test("session overrides counters and history cannot leak to another session")
    func sessionIsolation() async throws {
        let root = await store()
        let id = try await root.createRuntimeSession()
        let session = try #require(await root.runtimeSession(id))
        await session.setVariantOverride(for: "GET /users", variant: "error")
        _ = await session.incrementCallCount(for: "GET /users")
        #expect(await root.activeVariantOverride(for: "GET /users") == nil)
        #expect(await root.currentCallCount(for: "GET /users") == 0)
        await root.removeRuntimeSession(id)
        #expect(await root.runtimeSession(id) == nil)
    }

    @Test("history retains only the latest 500 requests")
    func boundedHistory() async {
        let store = await store()
        for index in 0..<501 {
            await store.recordRequest(
                RequestTrace(
                    id: "\(index)", timestamp: 0, method: "GET", path: "/users",
                    endpoint: "GET /users", status: 200, variant: "success", reason: "declared default",
                    callNumber: index))
        }
        let history = await store.recentRequests()
        #expect(history.count == 500)
        #expect(history.first?.id == "500")
        #expect(history.last?.id == "1")
    }

    @Test("admin scenarios and session headers drive responses and explain selection")
    func runtimeAPI() async throws {
        let store = await store()
        let app = try await buildApp(store: store)
        try await app.testing().test(
            .PUT, "/_admin/scenarios",
            beforeRequest: { req async throws in
                try req.content.encode(RuntimeScenario(name: "failure", overrides: ["GET /users": "error"]))
            }, afterResponse: { res async in #expect(res.status == .noContent) })
        let id = try await store.createRuntimeSession()
        try await app.testing().test(
            .PUT, "/_admin/scenario",
            beforeRequest: { req async throws in
                req.headers.add(name: "X-Mock-Session", value: id)
                try req.content.encode(["name": "failure"])
            }, afterResponse: { res async in #expect(res.status == .noContent) })
        try await app.testing().test(
            .GET, "/users",
            beforeRequest: { req async throws in
                req.headers.add(name: "X-Mock-Session", value: id)
            }, afterResponse: { res async in #expect(res.status == .internalServerError) })
        try await app.testing().test(.GET, "/users", afterResponse: { res async in #expect(res.status == .ok) })
        let session = try #require(await store.runtimeSession(id))
        let history = await session.recentRequests()
        #expect(history.first?.variant == "error")
        #expect(history.first?.reason == "runtime override")
        try await app.testing().test(
            .DELETE, "/_admin/endpoints/GET/users/state",
            beforeRequest: { req async throws in
                req.headers.add(name: "X-Mock-Session", value: id)
            }, afterResponse: { res async in #expect(res.status == .ok) })
        #expect(await session.currentCallCount(for: "GET /users") == 0)
        try await app.asyncShutdown()
    }

    @Test("new admin routes enforce configured authentication")
    func adminAuthentication() async throws {
        let store = await store()
        let config = ServerConfig(admin: .init(bearerToken: "secret"))
        let app = try await buildApp(store: store, config: config)
        try await app.testing().test(
            .GET, "/_admin/requests", afterResponse: { res async in #expect(res.status == .unauthorized) })
        try await app.testing().test(
            .POST, "/_admin/sessions", afterResponse: { res async in #expect(res.status == .unauthorized) })
        try await app.asyncShutdown()
    }
}
