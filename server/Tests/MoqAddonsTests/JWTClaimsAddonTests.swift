import Foundation
import Testing

@testable import MoqAddonKit
@testable import MoqAddons
@testable import MoqCore

/// Builds an unsigned JWS compact token (`alg: none`, empty signature), the shape the Firebase
/// Auth emulator issues.
func unsignedToken(_ claims: [String: Any]) throws -> String {
    func encode(_ object: [String: Any]) throws -> String {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    return try "\(encode(["alg": "none", "typ": "JWT"])).\(encode(claims))."
}

struct JWTClaimsAddonTests {
    let config: AnyCodableValue = .object(["verify_signature": .bool(false)])

    func makeAddon(
        _ config: AnyCodableValue? = nil, environment: AddonEnvironment = AddonEnvironment()
    ) throws -> JWTClaimsAddon {
        try JWTClaimsAddon(config: config ?? self.config, environment: environment)
    }

    func request(authorization: String?) -> AddonRequest {
        AddonRequest(
            method: "GET", path: "/me", headers: authorization.map { ["Authorization": $0] } ?? [:])
    }

    // MARK: - H2

    @Test("Decodes the claims of an unsigned emulator-style bearer token")
    func decodesUnsignedToken() async throws {
        let token = try unsignedToken(["sub": "user-1", "premium": true, "firebase": ["sign_in_provider": "password"]])
        let facts = try await makeAddon().enrich(request(authorization: "Bearer \(token)"))
        #expect(
            facts
                == .object([
                    "sub": .string("user-1"), "premium": .bool(true),
                    "firebase": .object(["sign_in_provider": .string("password")]),
                ]))
    }

    @Test("Scheme match is case-insensitive")
    func schemeCaseInsensitive() async throws {
        let token = try unsignedToken(["sub": "u"])
        #expect(try await makeAddon().enrich(request(authorization: "bearer \(token)")) != nil)
    }

    @Test("No facts for a missing header, another scheme, or a non-JWT value")
    func noFactsForUnusableHeaders() async throws {
        let addon = try makeAddon()
        #expect(await addon.enrich(request(authorization: nil)) == nil)
        #expect(await addon.enrich(request(authorization: "Basic dXNlcjpwYXNz")) == nil)
        #expect(await addon.enrich(request(authorization: "Bearer opaque-token")) == nil)
        #expect(await addon.enrich(request(authorization: "Bearer a.!!!.c")) == nil)
    }

    @Test("A custom header with no scheme reads the raw value")
    func customHeaderNoScheme() async throws {
        let addon = try makeAddon(
            .object(["verify_signature": .bool(false), "header": .string("X-Id-Token"), "scheme": .string("")]))
        let token = try unsignedToken(["sub": "u"])
        let facts = await addon.enrich(AddonRequest(method: "GET", path: "/", headers: ["x-id-token": token]))
        #expect(facts == .object(["sub": .string("u")]))
    }

    // MARK: - H5

    @Test("Claim specs match as a subset, recursing into nested claims")
    func subsetMatching() throws {
        let addon = try makeAddon()
        let facts: AnyCodableValue = .object([
            "sub": .string("u1"), "premium": .bool(true), "level": .int(3),
            "firebase": .object(["sign_in_provider": .string("password"), "tenant": .null]),
        ])
        func spec(_ claims: [String: AnyCodableValue]) -> AnyCodableValue { .object(["claims": .object(claims)]) }

        #expect(addon.matches(spec(["premium": .bool(true)]), facts: facts))
        #expect(addon.matches(spec(["level": .double(3)]), facts: facts))
        #expect(addon.matches(spec(["firebase": .object(["sign_in_provider": .string("password")])]), facts: facts))
        #expect(!addon.matches(spec(["premium": .bool(false)]), facts: facts))
        #expect(!addon.matches(spec(["missing": .string("x")]), facts: facts))
        #expect(!addon.matches(spec(["premium": .bool(true)]), facts: nil))
    }

    // MARK: - H7

    @Test("Records configured scalar claims on the trace")
    func traceAnnotations() throws {
        let facts: AnyCodableValue = .object(["sub": .string("u1"), "n": .int(2), "o": .object([:])])
        #expect(try makeAddon().traceAnnotations(facts: facts) == ["sub": "u1"])
        let custom = try makeAddon(
            .object(["verify_signature": .bool(false), "trace_claims": .array([.string("n"), .string("o")])]))
        #expect(custom.traceAnnotations(facts: facts) == ["n": "2"])
        #expect(custom.traceAnnotations(facts: nil).isEmpty)
    }

    // MARK: - H8

    @Test("verify_signature must be set explicitly, and to false")
    func verifySignatureRequired() {
        #expect(JWTClaimsAddon.validateConfig(.object([:])).map(\.field) == ["verify_signature"])
        #expect(JWTClaimsAddon.validateConfig(.object(["verify_signature": .bool(true)])).map(\.field) == ["verify_signature"])
        #expect(JWTClaimsAddon.validateConfig(.null).count == 1)
        #expect(JWTClaimsAddon.validateConfig(config).isEmpty)
    }

    @Test("Rejects unknown and ill-typed config keys")
    func rejectsBadConfig() {
        let diagnostics = JWTClaimsAddon.validateConfig(
            .object([
                "verify_signature": .bool(false), "header": .string(" "), "trace_claims": .string("sub"),
                "extra": .int(1),
            ]))
        #expect(Set(diagnostics.compactMap(\.field)) == ["header", "trace_claims", "extra"])
    }

    @Test("Match specs need a non-empty claims mapping and nothing else")
    func validatesMatchSpec() {
        #expect(JWTClaimsAddon.validateMatch(.object(["claims": .object(["premium": .bool(true)])])).isEmpty)
        #expect(!JWTClaimsAddon.validateMatch(.object(["claims": .object([:])])).isEmpty)
        #expect(!JWTClaimsAddon.validateMatch(.string("premium")).isEmpty)
        #expect(JWTClaimsAddon.validateMatch(.object(["claims": .object(["a": .int(1)]), "x": .int(1)])).map(\.field) == ["x"])
    }

    // MARK: - Activation (Q5: loopback only unless --allow-unverified-jwt)

    @Test("Refuses a non-loopback bind unless unverified tokens are explicitly allowed")
    func loopbackOnly() throws {
        #expect(throws: AddonActivationError.self) {
            try makeAddon(environment: AddonEnvironment(hostname: "0.0.0.0"))
        }
        _ = try makeAddon(environment: AddonEnvironment(hostname: "0.0.0.0", allowUnverifiedJWT: true))
        _ = try makeAddon(environment: AddonEnvironment(hostname: "localhost"))
        _ = try makeAddon(environment: AddonEnvironment(hostname: "::1"))
    }
}

struct AddonCatalogTests {
    @Test("Activating an unknown add-on id fails instead of dropping it")
    func unknownIDFails() {
        #expect(throws: AddonActivationError.self) {
            try AddonCatalog.builtIn.activate(["nope": .object([:])], environment: AddonEnvironment())
        }
    }

    @Test("Activation rejects an invalid config")
    func invalidConfigFails() {
        #expect(throws: AddonActivationError.self) {
            try AddonCatalog.builtIn.activate(["jwt-claims": .object([:])], environment: AddonEnvironment())
        }
    }

    @Test("Specs for an inactive add-on never match")
    func inactiveSpecNeverMatches() throws {
        let active = try AddonCatalog.builtIn.activate(nil, environment: AddonEnvironment())
        #expect(active.isEmpty)
        #expect(!active.matches(["jwt-claims": .object(["claims": .object(["a": .int(1)])])], facts: AddonFacts()))
        #expect(active.matches([:], facts: AddonFacts()))
    }

    @Test("Facts and trace annotations are keyed by add-on id")
    func factsKeyedByID() async throws {
        let active = try AddonCatalog.builtIn.activate(
            ["jwt-claims": .object(["verify_signature": .bool(false)])], environment: AddonEnvironment())
        let token = try unsignedToken(["sub": "u9"])
        let facts = await active.facts(
            for: AddonRequest(method: "GET", path: "/", headers: ["Authorization": "Bearer \(token)"]))
        #expect(facts["jwt-claims"] == .object(["sub": .string("u9")]))
        #expect(active.traceAnnotations(facts: facts) == ["jwt-claims": ["sub": "u9"]])
    }
}
