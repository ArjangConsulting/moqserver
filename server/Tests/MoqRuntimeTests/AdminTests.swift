import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

@Suite("Admin API - Store Overrides")
struct AdminTests {
    func makeEndpoint(method: HTTPMethodValue, path: String, variants: [String] = ["default"]) -> Endpoint {
        Endpoint(
            key: EndpointKey(method: method, path: path),
            authRequirement: .none,
            variants: variants.map { name in
                ResponseVariant(
                    name: name,
                    statusCode: name == "default" ? .ok : .internalServerError,
                    body: Data("{}".utf8)
                )
            }
        )
    }

    @Test("Set and get variant override")
    func setAndGetVariantOverride() async {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))

        await store.setVariantOverride(for: "GET /pets", variant: "error-500")
        let override = await store.activeVariantOverride(for: "GET /pets")
        #expect(override == "error-500")
    }

    @Test("Reset variant override")
    func resetVariantOverride() async {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))

        await store.setVariantOverride(for: "GET /pets", variant: "error-500")
        await store.resetVariantOverride(for: "GET /pets")
        let override = await store.activeVariantOverride(for: "GET /pets")
        #expect(override == nil)
    }

    @Test("All variant overrides")
    func allVariantOverrides() async {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))
        await store.register(makeEndpoint(method: .post, path: "/pets"))

        await store.setVariantOverride(for: "GET /pets", variant: "error-500")
        await store.setVariantOverride(for: "POST /pets", variant: "error-400")

        let overrides = await store.allVariantOverrides()
        #expect(overrides.count == 2)
        #expect(overrides["GET /pets"] == "error-500")
        #expect(overrides["POST /pets"] == "error-400")
    }

    @Test("Merge variants from mock file")
    func mergeVariants() async {
        let store = InMemoryMockStore()

        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default"]))

        let mockEndpoint = Endpoint(
            key: EndpointKey(method: .get, path: "/pets"),
            authRequirement: .none,
            variants: [
                ResponseVariant(name: "default", body: Data(#"{"mock":true}"#.utf8)),
                ResponseVariant(
                    name: "error-500", statusCode: .internalServerError, body: Data(#"{"error":"mock"}"#.utf8)),
            ]
        )
        await store.mergeVariants(from: mockEndpoint)

        let endpoint = await store.lookup(method: .get, path: "/pets")
        #expect(endpoint != nil)
        #expect(endpoint?.variants.count == 2)

        let defaultVariant = endpoint?.variant(named: "default")
        if let body = defaultVariant?.body {
            let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            #expect(json?["mock"] as? Bool == true)
        }
    }

    @Test("Admin API enforces bearer auth when configured")
    func adminAuthEnforced() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))

        let config = ServerConfig(
            admin: .init(
                bearerToken: "admin-token",
                apiKeyHeader: nil,
                apiKey: nil
            )
        )
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            #expect(res.status == .unauthorized)
        }

        try await app.testing().test(.GET, "/_admin/endpoints", headers: ["Authorization": "Bearer admin-token"]) {
            res async in
            #expect(res.status == .ok)
        }
    }

    @Test("Variant overrides persist across store instances when configured")
    func variantOverridePersistence() async {
        let tempFile = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("moqserver-override-\(UUID().uuidString).json")
            .path

        let store1 = InMemoryMockStore()
        await store1.configureVariantOverridePersistence(path: tempFile)
        await store1.setVariantOverride(for: "GET /pets", variant: "error-500")

        let store2 = InMemoryMockStore()
        await store2.configureVariantOverridePersistence(path: tempFile)
        let loaded = await store2.activeVariantOverride(for: "GET /pets")
        #expect(loaded == "error-500")
    }

    // MARK: - Admin API HTTP Route Tests

    @Test("GET /_admin/endpoints lists all endpoints")
    func listEndpointsHTTP() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))
        await store.register(makeEndpoint(method: .post, path: "/pets", variants: ["default"]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            #expect(res.status == .ok)
            let items = try? res.content.decode([EndpointListItem].self)
            #expect(items?.count == 2)
            // Sorted by method+path
            #expect(items?.first?.method == "GET")
            #expect(items?.first?.variants.count == 2)
        }
    }

    @Test("GET /_admin/endpoints/:method/** returns endpoint detail")
    func getEndpointDetailHTTP() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints/GET/pets") { res async in
            #expect(res.status == .ok)
            let detail = try? res.content.decode(EndpointDetail.self)
            #expect(detail?.method == "GET")
            #expect(detail?.path == "/pets")
            #expect(detail?.variants.count == 2)
            #expect(detail?.authRequirement == "none")
        }
    }

    @Test("GET /_admin/endpoints/:method/** returns 404 for unknown endpoint")
    func getEndpointNotFound() async throws {
        let store = InMemoryMockStore()
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints/GET/nonexistent") { res async in
            #expect(res.status == .notFound)
        }
    }

    @Test("GET /_admin/endpoints/:method/** gives hint when path exists with different method")
    func getEndpointWrongMethod() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .post, path: "/pets"))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints/GET/pets") { res async in
            #expect(res.status == .notFound)
            let body = String(buffer: res.body)
            #expect(body.contains("POST"))
        }
    }

    @Test("PUT /_admin/endpoints/:method/**/variant sets variant")
    func setVariantHTTP() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .PUT, "/_admin/endpoints/GET/pets/variant",
            beforeRequest: { req async throws in
                try req.content.encode(SetVariantRequest(variant: "error-500"))
            }
        ) { res async in
            #expect(res.status == .ok)
            let msg = try? res.content.decode(MessageResponse.self)
            #expect(msg?.message.contains("error-500") == true)
        }

        // Verify override was set
        let override = await store.activeVariantOverride(for: "GET /pets")
        #expect(override == "error-500")
    }

    @Test("PUT /_admin/endpoints/:method/**/variant rejects unknown variant")
    func setVariantNotFound() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default"]))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .PUT, "/_admin/endpoints/GET/pets/variant",
            beforeRequest: { req async throws in
                try req.content.encode(SetVariantRequest(variant: "nonexistent"))
            }
        ) { res async in
            #expect(res.status == .badRequest)
        }
    }

    @Test("DELETE /_admin/endpoints/:method/**/variant resets variant")
    func resetVariantHTTP() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))
        await store.setVariantOverride(for: "GET /pets", variant: "error-500")

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.DELETE, "/_admin/endpoints/GET/pets/variant") { res async in
            #expect(res.status == .ok)
        }

        let override = await store.activeVariantOverride(for: "GET /pets")
        #expect(override == nil)
    }

    @Test("Admin API key auth works")
    func adminAPIKeyAuth() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))

        let config = ServerConfig(
            admin: .init(apiKeyHeader: "X-Admin-Key", apiKey: "secret-key")
        )
        let app = try await buildApp(store: store, config: config)
        defer { Task { try? await app.asyncShutdown() } }

        // Without key
        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            #expect(res.status == .unauthorized)
        }

        // With correct key
        try await app.testing().test(.GET, "/_admin/endpoints", headers: ["X-Admin-Key": "secret-key"]) { res async in
            #expect(res.status == .ok)
        }

        // With wrong key
        try await app.testing().test(.GET, "/_admin/endpoints", headers: ["X-Admin-Key": "wrong-key"]) { res async in
            #expect(res.status == .unauthorized)
        }
    }

    @Test("Admin endpoint detail shows auth requirement strings")
    func adminEndpointAuthStrings() async throws {
        let store = InMemoryMockStore()
        let endpoint = Endpoint(
            key: EndpointKey(method: .get, path: "/secure"),
            authRequirement: .oauth2(scopes: ["read", "write"]),
            variants: [ResponseVariant(name: "default", body: Data("{}".utf8))]
        )
        await store.register(endpoint)

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints/GET/secure") { res async in
            #expect(res.status == .ok)
            let detail = try? res.content.decode(EndpointDetail.self)
            #expect(detail?.authRequirement == "oauth2(read, write)")
        }
    }

    @Test("Admin lists endpoints sorted by method and path")
    func adminEndpointsSorted() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .post, path: "/zebra"))
        await store.register(makeEndpoint(method: .get, path: "/alpha"))
        await store.register(makeEndpoint(method: .delete, path: "/beta"))

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            let items = try? res.content.decode([EndpointListItem].self)
            #expect(items?.count == 3)
            #expect(items?[0].method == "DELETE")
            #expect(items?[1].method == "GET")
            #expect(items?[2].method == "POST")
        }
    }

    @Test("Admin shows active variant in list")
    func adminListShowsActiveVariant() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets", variants: ["default", "error-500"]))
        await store.setVariantOverride(for: "GET /pets", variant: "error-500")

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            let items = try? res.content.decode([EndpointListItem].self)
            #expect(items?.first?.activeVariant == "error-500")
        }
    }

    @Test("Admin no auth required when no config")
    func adminNoAuth() async throws {
        let store = InMemoryMockStore()
        await store.register(makeEndpoint(method: .get, path: "/pets"))

        let app = try await buildApp(store: store, config: nil)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/_admin/endpoints") { res async in
            #expect(res.status == .ok)
        }
    }
}
