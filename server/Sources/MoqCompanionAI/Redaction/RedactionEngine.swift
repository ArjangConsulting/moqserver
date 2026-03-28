import Foundation

/// Result of redacting a string payload.
public struct RedactionResult: Sendable {
    /// The redacted text.
    public let text: String
    /// Number of redactions applied.
    public let redactionCount: Int
    /// Names of rules that matched.
    public let appliedRules: [String]
}

/// Applies redaction policy to text before sending to hosted AI providers.
public struct RedactionEngine: Sendable {
    private let policy: RedactionPolicy

    public init(policy: RedactionPolicy = .default) {
        self.policy = policy
    }

    /// Redact sensitive content from a text payload.
    public func redact(_ text: String) -> RedactionResult {
        var current = text
        var totalCount = 0
        var matchedRules: [String] = []

        for rule in policy.rules {
            let range = NSRange(current.startIndex..., in: current)
            let matchCount = rule.pattern.numberOfMatches(in: current, range: range)
            if matchCount > 0 {
                current = rule.pattern.stringByReplacingMatches(
                    in: current,
                    range: range,
                    withTemplate: rule.replacement
                )
                totalCount += matchCount
                matchedRules.append(rule.name)
            }
        }

        return RedactionResult(text: current, redactionCount: totalCount, appliedRules: matchedRules)
    }

    /// Check if a payload should be rejected outright (too many secrets, likely unsafe).
    public func shouldReject(_ text: String, threshold: Int = 50) -> Bool {
        var count = 0
        for rule in policy.rules {
            let range = NSRange(text.startIndex..., in: text)
            count += rule.pattern.numberOfMatches(in: text, range: range)
            if count >= threshold {
                return true
            }
        }
        return false
    }

    /// Redact for a specific provider kind. Local providers skip redaction.
    public func redactIfNeeded(_ text: String, providerKind: ProviderKind) -> RedactionResult {
        switch providerKind {
        case .local:
            return RedactionResult(text: text, redactionCount: 0, appliedRules: [])
        case .hosted:
            return redact(text)
        }
    }
}
