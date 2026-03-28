import Foundation

/// A single redaction rule that matches and masks sensitive content.
public struct RedactionRule: Sendable {
    public let name: String
    public let pattern: NSRegularExpression
    public let replacement: String

    public init(name: String, pattern: String, replacement: String = "[REDACTED]") throws {
        self.name = name
        self.pattern = try NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        self.replacement = replacement
    }
}

/// Collection of redaction rules applied before forwarding payloads to hosted providers.
public struct RedactionPolicy: Sendable {
    public let rules: [RedactionRule]

    public init(rules: [RedactionRule]) {
        self.rules = rules
    }

    /// Default policy per COMPANION_API.md: strip bearer tokens, API keys, cookies.
    public static let `default`: RedactionPolicy = {
        var rules: [RedactionRule] = []

        func add(_ name: String, _ pattern: String, _ replacement: String = "[REDACTED]") {
            if let rule = try? RedactionRule(name: name, pattern: pattern, replacement: replacement) {
                rules.append(rule)
            }
        }

        // Bearer tokens
        add("bearer_token", #"(Bearer\s+)[A-Za-z0-9\-._~+/]+=*"#, "$1[REDACTED]")

        // Authorization header values (Basic, etc.)
        add("basic_auth", #"(Basic\s+)[A-Za-z0-9+/]+=*"#, "$1[REDACTED]")

        // API keys in common header patterns
        add("api_key_header", #"((?:x-api-key|api-key|apikey|api_key)\s*[:=]\s*)"([^"]*)"#, #"$1"[REDACTED]""#)
        add("api_key_value", #"((?:x-api-key|api-key|apikey|api_key)\s*[:=]\s*)([^\s,}\]"]+)"#, "$1[REDACTED]")

        // Cookie values
        add("cookie_header", #"(Cookie\s*:\s*)([^\r\n]+)"#, "$1[REDACTED]")
        add("set_cookie_header", #"(Set-Cookie\s*:\s*)([^\r\n]+)"#, "$1[REDACTED]")

        // JWT tokens (three base64 segments separated by dots)
        add("jwt_token", #"eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"#, "[REDACTED_JWT]")

        // Common secret patterns in JSON
        add("json_secret", #"("(?:secret|password|token|apiKey|api_key|access_token|refresh_token|client_secret)"\s*:\s*)"([^"]+)""#, #"$1"[REDACTED]""#)

        // AWS-style keys
        add("aws_key", #"(?:AKIA|ASIA)[A-Z0-9]{16}"#, "[REDACTED_AWS_KEY]")

        return RedactionPolicy(rules: rules)
    }()
}
