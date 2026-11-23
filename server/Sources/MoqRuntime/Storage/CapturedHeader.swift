import Crypto
import Foundation
import MoqCore

/// One request header recorded in history by `serve --capture-request-headers`.
///
/// Headers that ``SensitiveHeaders`` marks as credentials (by name, name keyword, `--redact-header`,
/// or a JWT in the value) never store their value. They keep only what a test needs to
/// assert on: the auth scheme, a fingerprint of the credential, and, for `Cookie`, the cookie names.
public struct CapturedHeader: Codable, Sendable, Equatable {
    /// Longest non-sensitive value kept; longer values are cut and marked `truncated`.
    public static let valueLimit = 256

    /// Lowercased header name.
    public let name: String
    /// The value, for non-sensitive headers only.
    public let value: String?
    public let truncated: Bool
    public let redacted: Bool
    /// The auth scheme of a redacted credential, e.g. `Bearer`, when the value has one.
    public let scheme: String?
    /// First 16 hex characters of the SHA-256 of the credential (the value after `scheme`, or the
    /// whole value). Lets a test check which token was sent without storing it.
    public let fingerprint: String?
    /// Cookie names sent in a `Cookie` header, values omitted.
    public let cookieNames: [String]?

    public init(
        name: String, value: String?, truncated: Bool = false, redacted: Bool = false, scheme: String? = nil,
        fingerprint: String? = nil, cookieNames: [String]? = nil
    ) {
        self.name = name
        self.value = value
        self.truncated = truncated
        self.redacted = redacted
        self.scheme = scheme
        self.fingerprint = fingerprint
        self.cookieNames = cookieNames
    }

    /// Records one header, redacting it when `SensitiveHeaders` says it is a credential.
    /// `redactHeaders` holds extra lowercased names from `--redact-header`.
    public static func capture(name: String, value: String, redactHeaders: Set<String> = []) -> CapturedHeader {
        let lowered = name.lowercased()
        guard SensitiveHeaders.isCredential(name: lowered, value: value, additionalNames: redactHeaders) else {
            let truncated = value.count > valueLimit
            return CapturedHeader(
                name: lowered, value: truncated ? String(value.prefix(valueLimit)) : value, truncated: truncated)
        }
        if lowered == "cookie" {
            let names = value.split(separator: ";").compactMap { component -> String? in
                let name = component.split(separator: "=", maxSplits: 1).first?
                    .trimmingCharacters(in: .whitespaces)
                return name.flatMap { $0.isEmpty ? nil : $0 }
            }
            return CapturedHeader(name: lowered, value: nil, redacted: true, cookieNames: names)
        }
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        let parts = trimmed.split(separator: " ", maxSplits: 1)
        let hasScheme =
            parts.count == 2 && (lowered == "authorization" || lowered == "proxy-authorization")
        let credential = hasScheme ? parts[1].trimmingCharacters(in: .whitespaces) : trimmed
        return CapturedHeader(
            name: lowered, value: nil, redacted: true, scheme: hasScheme ? String(parts[0]) : nil,
            fingerprint: fingerprint(of: credential))
    }

    /// The fingerprint ``capture(name:value:)`` stores for `credential`.
    public static func fingerprint(of credential: String) -> String {
        SHA256.hash(data: Data(credential.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}
