import Foundation
import Testing
import Vapor
import VaporTesting

@testable import MoqCore
@testable import MoqRuntime

private func graphQLEndpoint(operation: EndpointOperation, variants: [ResponseVariant]) -> Endpoint {
    Endpoint(
        key: EndpointKey(method: .post, path: "/graphql"), authRequirement: .none, variants: variants,
        operation: operation)
}

private func graphQLBody(_ operationName: String) -> ByteBuffer {
    ByteBuffer(string: #"{"query":"query \#(operationName) { x }","operationName":"\#(operationName)"}"#)
}

@Suite("GraphQL per-operation runtime state")
struct GraphQLOperationStateTests {
    private func store() async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        await store.register(
            graphQLEndpoint(
                operation: EndpointOperation(type: .query, name: "GetUser"),
                variants: [
                    ResponseVariant(name: "first", statusCode: .ok, body: Data("user-1".utf8), callCount: 1),
                    ResponseVariant(name: "later", statusCode: .ok, body: Data("user-n".utf8)),
                    ResponseVariant(name: "error", statusCode: .internalServerError, body: Data("boom".utf8)),
                ]))
        await store.register(
            graphQLEndpoint(
                operation: EndpointOperation(type: .query, name: "GetCart"),
                variants: [
                    ResponseVariant(name: "first", statusCode: .ok, body: Data("cart-1".utf8), callCount: 1),
                    ResponseVariant(name: "later", statusCode: .ok, body: Data("cart-n".utf8)),
                ]))
        return store
    }

    @Test("Each operation keeps its own call count")
    func separateCallCounts() async throws {
        let store = await store()
        let app = try await buildApp(store: store)
        let json: HTTPHeaders = ["Content-Type": "application/json"]
        try await app.testing().test(.POST, "/graphql", headers: json, body: graphQLBody("GetUser")) { res async in
            #expect(res.body.string == "user-1")
        }
        try await app.testing().test(.POST, "/graphql", headers: json, body: graphQLBody("GetCart")) { res async in
            #expect(res.body.string == "cart-1")
        }
        #expect(await store.currentCallCount(for: "POST /graphql#GetUser") == 1)
        #expect(await store.currentCallCount(for: "POST /graphql#GetCart") == 1)
        #expect(await store.currentCallCount(for: "POST /graphql") == 0)
        try await app.asyncShutdown()
    }

    @Test("An admin override addressed with ?operation= affects only that operation")
    func perOperationOverride() async throws {
        let store = await store()
        let app = try await buildApp(store: store)
        let json: HTTPHeaders = ["Content-Type": "application/json"]
        try await app.testing().test(
            .PUT, "/_admin/endpoints/POST/graphql/variant?operation=GetUser", headers: json,
            body: ByteBuffer(string: #"{"variant":"error"}"#)
        ) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.POST, "/graphql", headers: json, body: graphQLBody("GetUser")) { res async in
            #expect(res.status == .internalServerError)
        }
        try await app.testing().test(.POST, "/graphql", headers: json, body: graphQLBody("GetCart")) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            let items = try? JSONDecoder().decode([EndpointListItem].self, from: Data(buffer: res.body))
            #expect(items?.map(\.key) == ["POST /graphql#GetCart", "POST /graphql#GetUser"])
            #expect(items?.first { $0.operation == "GetUser" }?.activeVariant == "error")
        }
        try await app.testing().test(.GET, "/_admin/endpoints/POST/graphql") { res async in
            #expect(res.status == .notFound)
            #expect(res.body.string.contains("?operation="))
        }
        try await app.asyncShutdown()
    }

    @Test("Runtime scenarios accept GraphQL operation keys")
    func scenarioWithOperationKey() async throws {
        let store = await store()
        try await store.defineScenario(
            RuntimeScenario(name: "user-down", overrides: ["POST /graphql#GetUser": "error"]))
        try await store.activateScenario("user-down")
        #expect(await store.activeVariantOverride(for: "POST /graphql#GetUser") == "error")
    }

    @Test("Anonymous operations get a stable document-hash selector")
    func anonymousSelector() {
        let operation = EndpointOperation(type: .query, document: "query {  me { id } }")
        let same = EndpointOperation(type: .query, document: "query { me { id } }")
        #expect(operation.stateSelector == same.stateSelector)
        #expect(operation.stateSelector.hasPrefix("query:"))
        #expect(operation.stateSelector.count == "query:".count + 16)
    }
}

@Suite("API-key auth nested in allOf/anyOf over HTTP")
struct NestedAPIKeyAuthTests {
    let config = ServerConfig(auth: .init(bearerTokens: ["valid-bearer"], apiKeys: ["X-API-Key": "valid-key"]))

    @Test("allOf(bearer, apiKey) needs both headers")
    func allOfBearerAndKey() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/secure", auth: .allOf([.bearer, .apiKey(headerName: "X-API-Key")])))
        let app = try await buildApp(store: store, config: config)
        try await app.testing().test(
            .GET, "/secure", headers: ["Authorization": "Bearer valid-bearer", "X-API-Key": "valid-key"]
        ) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/secure", headers: ["Authorization": "Bearer valid-bearer"]) { res async in
            #expect(res.status == .unauthorized)
        }
        try await app.asyncShutdown()
    }

    @Test("anyOf(bearer, apiKey) accepts the key alone")
    func anyOfKeyAlone() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/secure", auth: .anyOf([.bearer, .apiKey(headerName: "X-API-Key")])))
        let app = try await buildApp(store: store, config: config)
        try await app.testing().test(.GET, "/secure", headers: ["X-API-Key": "valid-key"]) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/secure", headers: ["X-API-Key": "wrong"]) { res async in
            #expect(res.status == .unauthorized)
        }
        try await app.asyncShutdown()
    }
}

@Suite("Accept negotiation with variants that have nothing to negotiate")
struct AcceptFallbackTests {
    @Test("A bodyless 204 answers Accept: text/html instead of 406")
    func bodylessVariantIsAcceptable() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .delete, path: "/items/{id}",
                variants: [ResponseVariant(name: "deleted", isDefault: true, statusCode: HTTPStatusCode(code: 204))]))
        let app = try await buildApp(store: store)
        try await app.testing().test(.DELETE, "/items/1", headers: ["Accept": "text/html"]) { res async in
            #expect(res.status == .noContent)
        }
        try await app.asyncShutdown()
    }

    @Test("A variant without Content-Type is acceptable, but q=0 still excludes it")
    func untypedVariant() async throws {
        let store = InMemoryMockStore()
        await store.register(
            makeTestEndpoint(
                method: .get, path: "/blob",
                variants: [ResponseVariant(name: "raw", headers: [], body: Data("bytes".utf8))]))
        let app = try await buildApp(store: store)
        try await app.testing().test(.GET, "/blob", headers: ["Accept": "application/xml"]) { res async in
            #expect(res.status == .ok)
        }
        try await app.testing().test(.GET, "/blob", headers: ["Accept": "*/*;q=0"]) { res async in
            #expect(res.status == .notAcceptable)
        }
        try await app.asyncShutdown()
    }
}

@Suite("Runtime session expiry and listing")
struct RuntimeSessionExpiryTests {
    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value = Date(timeIntervalSince1970: 1_000)
        func now() -> Date { lock.withLock { value } }
        func advance(_ seconds: TimeInterval) { lock.withLock { value += seconds } }
    }

    @Test("Idle sessions expire and free capacity; used sessions stay alive")
    func idleExpiry() async throws {
        let clock = Clock()
        let store = InMemoryMockStore()
        await store.setClock { clock.now() }
        await store.configureSessionExpiry(idleTimeout: 60)
        let idle = try await store.createRuntimeSession()
        let active = try await store.createRuntimeSession()

        clock.advance(45)
        #expect(await store.runtimeSession(active) != nil)
        clock.advance(30)
        #expect(await store.runtimeSession(idle) == nil)
        #expect(await store.listRuntimeSessions().map(\.id) == [active])
        #expect(await store.listRuntimeSessions().first?.expiresAt == 1_000 + 45 + 60)
    }

    @Test("Capacity errors clear once stale sessions expire")
    func capacityRecovers() async throws {
        let clock = Clock()
        let store = InMemoryMockStore()
        await store.setClock { clock.now() }
        await store.configureSessionExpiry(idleTimeout: 60)
        for _ in 0..<InMemoryMockStore.maxRuntimeSessions { _ = try await store.createRuntimeSession() }
        await #expect(throws: RuntimeScenarioError.self) { try await store.createRuntimeSession() }
        clock.advance(61)
        _ = try await store.createRuntimeSession()
    }

    @Test("GET /_admin/sessions lists live sessions; a capacity 409 reports code conflict")
    func listSessionsRoute() async throws {
        let store = InMemoryMockStore()
        await store.configureSessionExpiry(idleTimeout: nil)
        let app = try await buildApp(store: store)
        let id = try await store.createRuntimeSession()
        try await app.testing().test(.GET, "/_admin/sessions") { res async in
            let sessions = try? JSONDecoder().decode([RuntimeSessionInfo].self, from: Data(buffer: res.body))
            #expect(sessions?.map(\.id) == [id])
            #expect(sessions?.first?.expiresAt == nil)
        }
        for _ in 1..<InMemoryMockStore.maxRuntimeSessions { _ = try await store.createRuntimeSession() }
        try await app.testing().test(.POST, "/_admin/sessions") { res async in
            #expect(res.status == .conflict)
            let body = try? JSONDecoder().decode(ErrorResponse.self, from: Data(buffer: res.body))
            #expect(body?.code == "conflict")
        }
        try await app.asyncShutdown()
    }
}

@Suite("POST /_admin/reload")
struct ProjectReloadTests {
    final class Source: @unchecked Sendable {
        private let lock = NSLock()
        private var snapshot: RuntimeProjectSnapshot
        private var failure: (any Error)?
        init(_ snapshot: RuntimeProjectSnapshot) { self.snapshot = snapshot }
        func set(_ snapshot: RuntimeProjectSnapshot) { lock.withLock { self.snapshot = snapshot } }
        func fail(_ error: any Error) { lock.withLock { failure = error } }
        func load() throws -> RuntimeProjectSnapshot {
            try lock.withLock {
                if let failure { throw failure }
                return snapshot
            }
        }
    }

    struct Broken: Error {}

    private func endpoint(_ path: String, variants: [String] = ["default"]) -> Endpoint {
        makeTestEndpoint(
            method: .get, path: path,
            variants: variants.map { ResponseVariant(name: $0, isDefault: $0 == variants.first, body: Data($0.utf8)) })
    }

    @Test("Reload serves new routes, keeps valid state and reports dropped state")
    func reloadSwapsProject() async throws {
        let store = InMemoryMockStore()
        let original = [
            endpoint("/users", variants: ["default", "empty"]), endpoint("/legacy", variants: ["a", "b"]),
        ]
        for item in original { await store.register(item) }
        let source = Source(RuntimeProjectSnapshot(endpoints: original, scenarios: [:]))
        let app = try await buildApp(store: store, reloadSource: { try source.load() })
        let session = try await store.createRuntimeSession()
        await store.setVariantOverride(for: "GET /users", variant: "empty")
        await store.setVariantOverride(for: "GET /legacy", variant: "b")

        source.set(
            RuntimeProjectSnapshot(
                endpoints: [endpoint("/users", variants: ["default", "empty"]), endpoint("/orders")],
                scenarios: ["empty-users": ["GET /users": "empty"]]))
        try await app.testing().test(.POST, "/_admin/reload") { res async in
            #expect(res.status == .ok)
            let summary = try? JSONDecoder().decode(ReloadSummary.self, from: Data(buffer: res.body))
            #expect(summary?.endpoints == 2)
            #expect(summary?.sessions == 1)
            #expect(summary?.warnings == ["[global] dropped variant override GET /legacy → b"])
        }
        try await app.testing().test(.GET, "/orders") { res async in #expect(res.status == .ok) }
        try await app.testing().test(.GET, "/legacy") { res async in #expect(res.status == .notFound) }
        try await app.testing().test(.GET, "/users") { res async in #expect(res.body.string == "empty") }
        try await app.testing().test(.GET, "/orders", headers: ["X-Mock-Session": session]) { res async in
            #expect(res.status == .ok)
        }
        #expect(await store.allScenarios().map(\.name) == ["empty-users"])
        try await app.asyncShutdown()
    }

    @Test("A failed reload keeps serving the previous project")
    func failedReloadKeepsProject() async throws {
        let store = InMemoryMockStore()
        await store.register(endpoint("/users"))
        let source = Source(RuntimeProjectSnapshot(endpoints: [endpoint("/users")], scenarios: [:]))
        source.fail(Broken())
        let app = try await buildApp(store: store, reloadSource: { try source.load() })
        try await app.testing().test(.POST, "/_admin/reload") { res async in
            #expect(res.status == .unprocessableEntity)
        }
        try await app.testing().test(.GET, "/users") { res async in #expect(res.status == .ok) }
        try await app.asyncShutdown()
    }

    @Test("Without a reload source the route answers 501")
    func reloadUnavailable() async throws {
        let app = try await buildApp(store: InMemoryMockStore())
        try await app.testing().test(.POST, "/_admin/reload") { res async in
            #expect(res.status == .notImplemented)
        }
        try await app.asyncShutdown()
    }
}

@Suite("Config override keys are checked against the project")
struct ConfigOverrideWarningTests {
    @Test("Typos and unknown variants are reported, valid keys are not")
    func warnings() {
        let endpoints = [
            makeTestEndpoint(
                method: .get, path: "/users",
                variants: [ResponseVariant(name: "default"), ResponseVariant(name: "empty")])
        ]
        let config = ServerConfig(
            variantOverrides: ["GET /users": "empty", "get /users/": "empty", "GET /orders": "x", "GET /users ": "y"],
            delayOverrides: ["GET /users": 1, "POST /users": 2])
        let warnings = config.unknownOverrideWarnings(endpoints: endpoints)
        #expect(warnings.contains { $0.hasPrefix("variantOverrides[get /users/]") && $0.contains("`GET /users`") })
        #expect(warnings.contains { $0.hasPrefix("variantOverrides[GET /orders]") })
        #expect(warnings.contains { $0.hasPrefix("delayOverrides[POST /users]") })
        #expect(!warnings.contains { $0.hasPrefix("variantOverrides[GET /users]") })
        #expect(!warnings.contains { $0.hasPrefix("delayOverrides[GET /users]") })

        let badVariant = ServerConfig(variantOverrides: ["GET /users": "missing"])
        #expect(badVariant.unknownOverrideWarnings(endpoints: endpoints).first?.contains("no variant") == true)
    }
}
