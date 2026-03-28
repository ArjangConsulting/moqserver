import Foundation
import Testing
@testable import MoqCore

@Suite("AuthValidator - Unit Tests")
struct AuthValidatorTests {
    let config = AuthConfig(
        bearerTokens: ["valid-bearer"],
        basicCredentials: [BasicCredential(username: "admin", password: "pass")],
        apiKeys: ["X-API-Key": "valid-key"],
        oauth2Tokens: ["valid-oauth-token"],
        oauth2TokenScopes: ["valid-oauth-token": ["read", "write:pets"]]
    )

    @Test("No auth requirement always allows")
    func noAuth() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.none, context: AuthContext(authorizationHeader: nil))
        #expect(result == .allowed)
    }

    @Test("Bearer auth rejects missing token")
    func bearerMissing() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.bearer, context: AuthContext(authorizationHeader: nil))
        if case .unauthorized(let msg, _) = result {
            #expect(msg.contains("Bearer token required"))
        } else {
            Issue.record("Expected unauthorized")
        }
    }

    @Test("Bearer auth accepts valid token")
    func bearerValid() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.bearer, context: AuthContext(authorizationHeader: "Bearer valid-bearer"))
        #expect(result == .allowed)
    }

    @Test("Bearer auth rejects invalid token")
    func bearerInvalid() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.bearer, context: AuthContext(authorizationHeader: "Bearer wrong"))
        if case .unauthorized(let msg, _) = result {
            #expect(msg.contains("Invalid bearer token"))
        } else {
            Issue.record("Expected unauthorized")
        }
    }

    @Test("Basic auth rejects missing credentials")
    func basicMissing() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.basic, context: AuthContext(authorizationHeader: nil))
        if case .unauthorized(_, let challenge) = result {
            #expect(challenge?.contains("Basic") == true)
        } else {
            Issue.record("Expected unauthorized")
        }
    }

    @Test("Basic auth accepts valid credentials")
    func basicValid() {
        let validator = AuthValidator(config: config)
        let creds = Data("admin:pass".utf8).base64EncodedString()
        let result = validator.evaluate(.basic, context: AuthContext(authorizationHeader: "Basic \(creds)"))
        #expect(result == .allowed)
    }

    @Test("OAuth2 rejects missing token")
    func oauth2Missing() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.oauth2(scopes: ["read"]), context: AuthContext(authorizationHeader: nil))
        if case .unauthorized(_, let challenge) = result {
            #expect(challenge?.contains("scope=\"read\"") == true)
        } else {
            Issue.record("Expected unauthorized")
        }
    }

    @Test("OAuth2 accepts valid token with sufficient scopes")
    func oauth2Valid() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.oauth2(scopes: ["read"]), context: AuthContext(authorizationHeader: "Bearer valid-oauth-token"))
        #expect(result == .allowed)
    }

    @Test("OAuth2 rejects insufficient scope")
    func oauth2InsufficientScope() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(.oauth2(scopes: ["admin"]), context: AuthContext(authorizationHeader: "Bearer valid-oauth-token"))
        if case .forbidden(_, let challenge) = result {
            #expect(challenge?.contains("insufficient_scope") == true)
        } else {
            Issue.record("Expected forbidden, got \(result)")
        }
    }

    @Test("allOf requires all to pass")
    func allOf() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(
            .allOf([.bearer, .basic]),
            context: AuthContext(authorizationHeader: "Bearer valid-bearer")
        )
        // Basic will fail because "Bearer valid-bearer" doesn't start with "Basic"
        if case .unauthorized = result {
            // expected
        } else {
            Issue.record("Expected unauthorized for allOf with failing basic")
        }
    }

    @Test("anyOf allows if any passes")
    func anyOf() {
        let validator = AuthValidator(config: config)
        let result = validator.evaluate(
            .anyOf([.bearer, .basic]),
            context: AuthContext(authorizationHeader: "Bearer valid-bearer")
        )
        #expect(result == .allowed)
    }
}
