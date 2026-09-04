import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

@Suite("Content Negotiation")
struct ContentNegotiationTests {
    func makeMultiContentEndpoint() -> Endpoint {
        Endpoint(
            key: EndpointKey(method: .get, path: "/pets"),
            authRequirement: .none,
            variants: [
                ResponseVariant(
                    name: "default",
                    statusCode: .ok,
                    headers: [("Content-Type", "application/json")],
                    body: Data(#"[{"id":1,"name":"Fido"}]"#.utf8)
                ),
                ResponseVariant(
                    name: "default-xml",
                    statusCode: .ok,
                    headers: [("Content-Type", "application/xml")],
                    body: Data("<?xml version=\"1.0\"?>\n<pets><pet><id>1</id><name>Fido</name></pet></pets>".utf8)
                ),
                ResponseVariant(
                    name: "default-text",
                    statusCode: .ok,
                    headers: [("Content-Type", "text/plain")],
                    body: Data("Fido".utf8)
                ),
            ]
        )
    }

    /// Regression: two variants sharing a Content-Type were separated only by declaration
    /// order under a generic `Accept: */*`, so `isDefault` was ignored for essentially every
    /// real HTTP client — an error variant declared first shadowed the default success one.
    @Test("Default variant wins a content-negotiation tie regardless of declaration order")
    func defaultWinsTieOverDeclarationOrder() async throws {
        let endpoint = Endpoint(
            key: EndpointKey(method: .get, path: "/videos"),
            authRequirement: .none,
            variants: [
                ResponseVariant(
                    name: "serverError",
                    statusCode: .internalServerError,
                    headers: [("Content-Type", "application/json")],
                    body: Data(#"{"detail":"boom"}"#.utf8)
                ),
                ResponseVariant(
                    name: "success",
                    isDefault: true,
                    statusCode: .ok,
                    headers: [("Content-Type", "application/json")],
                    body: Data(#"{"id":1}"#.utf8)
                ),
            ]
        )
        let store = InMemoryMockStore()
        await store.register(endpoint)

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .GET, "/videos", beforeRequest: { req async in
                req.headers.replaceOrAdd(name: .accept, value: "*/*")
            }
        ) { res async in
            #expect(res.status == .ok)
        }
    }

    @Test("Returns JSON by default (no Accept header)")
    func defaultJson() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets") { res async in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/json")
        }
    }

    @Test("Returns XML when Accept: application/xml")
    func acceptXml() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "application/xml"]) { res async in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/xml")
            let body = String(buffer: res.body)
            #expect(body.contains("<?xml"))
        }
    }

    @Test("Returns text when Accept: text/plain")
    func acceptText() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "text/plain"]) { res async in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "text/plain")
        }
    }

    @Test("Respects quality factors in Accept header")
    func qualityFactors() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        // Prefer XML over JSON via quality factors
        try await app.testing().test(
            .GET, "/pets", headers: ["Accept": "application/json;q=0.5, application/xml;q=1.0"]
        ) { res async in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/xml")
        }
    }

    @Test("Wildcard Accept matches first variant")
    func wildcardAccept() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "*/*"]) { res async in
            #expect(res.status == .ok)
            // Should match first variant (JSON)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/json")
        }
    }

    @Test("Type wildcard matches subtype")
    func typeWildcard() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "text/*"]) { res async in
            #expect(res.status == .ok)
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "text/plain")
        }
    }

    @Test("X-Mock-Variant takes priority over Accept header")
    func variantOverridesAccept() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())

        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .GET, "/pets",
            headers: [
                "Accept": "application/xml",
                "X-Mock-Variant": "default",
            ]
        ) { res async in
            #expect(res.status == .ok)
            // Should return JSON because variant "default" is explicitly requested
            let contentType = res.headers.first(name: .contentType)
            #expect(contentType == "application/json")
        }
    }

    @Test("Accept q=0 is not selectable")
    func zeroQualityIsRejected() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "application/json;q=0"]) { res async in
            #expect(res.status == .notAcceptable)
        }
    }

    @Test("Specific q=0 exclusion overrides wildcard")
    func specificExclusionOverridesWildcard() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(
            .GET,
            "/pets",
            headers: ["Accept": "application/json;q=0, */*;q=1"]
        ) { res async in
            #expect(res.status == .ok)
            #expect(res.headers.first(name: .contentType) == "application/xml")
        }
    }

    @Test("Unmatched Accept does not fall back")
    func unmatchedAcceptIsRejected() async throws {
        let store = InMemoryMockStore()
        await store.register(makeMultiContentEndpoint())
        let app = try await buildApp(store: store)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "/pets", headers: ["Accept": "image/png"]) { res async in
            #expect(res.status == .notAcceptable)
        }
    }
}
