import Testing
@testable import MoqCompanionAI

@Suite("RedactionEngine Tests")
struct RedactionEngineTests {
    let engine = RedactionEngine()

    @Test("Redacts bearer tokens")
    func redactsBearerTokens() {
        let input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.abc123"
        let result = engine.redact(input)
        #expect(!result.text.contains("eyJhbGciOiJIUzI1NiJ9"))
        #expect(result.redactionCount > 0)
    }

    @Test("Redacts Basic auth")
    func redactsBasicAuth() {
        let input = "Authorization: Basic dXNlcjpwYXNz"
        let result = engine.redact(input)
        #expect(!result.text.contains("dXNlcjpwYXNz"))
        #expect(result.text.contains("Basic"))
        #expect(result.redactionCount > 0)
    }

    @Test("Redacts JSON secret fields")
    func redactsJsonSecrets() {
        let input = #"{"username": "admin", "password": "s3cret", "token": "abc123"}"#
        let result = engine.redact(input)
        #expect(!result.text.contains("s3cret"))
        #expect(!result.text.contains("abc123"))
        #expect(result.text.contains("[REDACTED]"))
    }

    @Test("Redacts cookie headers")
    func redactsCookieHeaders() {
        let input = "Cookie: session=abc123; csrf=xyz789"
        let result = engine.redact(input)
        #expect(!result.text.contains("abc123"))
        #expect(result.redactionCount > 0)
    }

    @Test("Redacts API key patterns")
    func redactsApiKeys() {
        let input = #"x-api-key: sk-1234567890abcdef"#
        let result = engine.redact(input)
        #expect(!result.text.contains("sk-1234567890abcdef"))
    }

    @Test("Preserves non-sensitive content")
    func preservesNonSensitiveContent() {
        let input = "GET /users/123 HTTP/1.1\nHost: api.example.com\nContent-Type: application/json"
        let result = engine.redact(input)
        #expect(result.text == input)
        #expect(result.redactionCount == 0)
    }

    @Test("Local providers skip redaction")
    func localProviderSkipsRedaction() {
        let input = "Bearer my-secret-token"
        let result = engine.redactIfNeeded(input, providerKind: .local)
        #expect(result.text == input)
        #expect(result.redactionCount == 0)
    }

    @Test("Hosted providers apply redaction")
    func hostedProviderAppliesRedaction() {
        let input = "Authorization: Bearer my-secret-token"
        let result = engine.redactIfNeeded(input, providerKind: .hosted)
        #expect(!result.text.contains("my-secret-token"))
        #expect(result.redactionCount > 0)
    }

    @Test("Should reject returns false for clean content")
    func shouldRejectCleanContent() {
        let input = "GET /users\nContent-Type: application/json"
        #expect(!engine.shouldReject(input))
    }
}
