import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqAddonKit
@testable import MoqAddons
@testable import MoqCore
@testable import MoqRuntime

struct OAuthMockAddonTests {
    private let form: HTTPHeaders = ["Content-Type": "application/x-www-form-urlencoded"]

    @Test("oauth-mock serves the same endpoints at /_addons/oauth-mock and the /_auth alias")
    func mountedAtBothPrefixes() async throws {
        let config = ServerConfig(auth: .init(oauth2Tokens: ["configured-token"]))
        let app = try await buildApp(store: InMemoryMockStore(), config: config)
        for path in ["/_addons/oauth-mock/token", "/_auth/token"] {
            try await app.testing().test(
                .POST, path, headers: form, body: ByteBuffer(string: "grant_type=client_credentials&client_id=a&client_secret=b")
            ) { res async in
                #expect(res.status == .ok)
                #expect(res.headers.first(name: "Cache-Control") == "no-store")
                #expect(res.body.string.contains("configured-token"))
            }
        }
        try await app.testing().test(
            .GET, "/_addons/oauth-mock/authorize?response_type=code&redirect_uri=http://localhost/callback&state=s1"
        ) { res async in
            #expect(res.status == .found)
            #expect(res.headers.first(name: "Location")?.hasPrefix("http://localhost/callback?code=mock-auth-code-") == true)
            #expect(res.headers.first(name: "Location")?.hasSuffix("&state=s1") == true)
        }
        try await app.asyncShutdown()
    }

    @Test("The token endpoint accepts a JSON body")
    func jsonBody() async throws {
        let app = try await buildApp(store: InMemoryMockStore())
        try await app.testing().test(
            .POST, "/_auth/token", headers: ["Content-Type": "application/json"],
            body: ByteBuffer(string: #"{"grant_type":"password","username":"u","password":"p","scope":"read"}"#)
        ) { res async in
            #expect(res.status == .ok)
            #expect(res.body.string.contains(#""scope":"read""#))
        }
        try await app.asyncShutdown()
    }

    @Test("A caller-supplied oauth-mock replaces the default one")
    func callerSuppliedInstance() async throws {
        let supplied = OAuthMockAddon(config: OAuthMockConfig(oauth2Tokens: ["supplied"]))
        let app = try await buildApp(
            store: InMemoryMockStore(), config: ServerConfig(auth: .init(oauth2Tokens: ["from-config"])),
            addons: ActiveAddons([supplied]))
        try await app.testing().test(
            .POST, "/_auth/token", headers: form, body: ByteBuffer(string: "grant_type=refresh_token&refresh_token=r")
        ) { res async in
            #expect(res.body.string.contains("supplied"))
        }
        try await app.asyncShutdown()
    }
}

struct AddonHTTPRequestTests {
    @Test("bodyParameters decodes form bodies, including + and percent escapes")
    func formBody() {
        let request = AddonHTTPRequest(
            method: "POST", path: "/", headers: ["Content-Type": "application/x-www-form-urlencoded; charset=utf-8"],
            body: Data("a=1&scope=read+write&redirect_uri=http%3A%2F%2Fx%2Fcb&flag".utf8))
        #expect(request.bodyParameters() == ["a": "1", "scope": "read write", "redirect_uri": "http://x/cb", "flag": ""])
    }

    @Test("bodyParameters keeps only string values from JSON and ignores other types")
    func jsonAndOtherBodies() {
        let json = AddonHTTPRequest(
            method: "POST", path: "/", headers: ["Content-Type": "application/json"],
            body: Data(#"{"a":"x","n":1}"#.utf8))
        #expect(json.bodyParameters() == ["a": "x"])
        let text = AddonHTTPRequest(method: "POST", path: "/", headers: ["Content-Type": "text/plain"], body: Data("a=1".utf8))
        #expect(text.bodyParameters().isEmpty)
    }

    @Test("oauth-mock config decodes from the add-on Codable boundary and validates redirect URIs")
    func configBoundary() throws {
        let bad: AnyCodableValue = .object(["oauth2RedirectUris": .array([.string("not a url")])])
        #expect(OAuthMockAddon.validateConfig(bad).map(\.field) == ["oauth2RedirectUris"])
        let good: AnyCodableValue = .object(["oauth2Tokens": .array([.string("t")])])
        let addon = try OAuthMockAddon(config: good, environment: AddonEnvironment())
        #expect(addon.config.oauth2Tokens == ["t"])
    }
}
