import Foundation
import MoqCore

/// One query parameter recorded in history by `serve --capture-request-query`.
///
/// Parameters are kept in the order the request sent them, repeats included, with names exactly as
/// sent (query names are case-sensitive). A parameter ``CredentialNames`` lists, or whose value
/// holds a JWT, never stores its value, only a fingerprint a test can compare against.
public struct CapturedQueryParameter: Codable, Sendable, Equatable {
    /// The name, percent-decoded, exactly as sent.
    public let name: String
    /// The percent-decoded value, for non-sensitive parameters only. Empty for `?flag` and `?flag=`.
    public let value: String?
    public let truncated: Bool
    public let redacted: Bool
    /// First 16 hex characters of the SHA-256 of a redacted value; see ``CapturedHeader/fingerprint(of:)``.
    public let fingerprint: String?

    public init(
        name: String, value: String?, truncated: Bool = false, redacted: Bool = false, fingerprint: String? = nil
    ) {
        self.name = name
        self.value = value
        self.truncated = truncated
        self.redacted = redacted
        self.fingerprint = fingerprint
    }

    /// Records one parameter, redacting it when `credentialNames` says it is a credential.
    public static func capture(
        name: String, value: String, credentialNames: CredentialNames = .builtIn
    ) -> CapturedQueryParameter {
        if credentialNames.isCredentialQueryParameter(name, value: value) {
            return CapturedQueryParameter(
                name: name, value: nil, redacted: true, fingerprint: CapturedHeader.fingerprint(of: value))
        }
        let truncated = value.count > CapturedHeader.valueLimit
        return CapturedQueryParameter(
            name: name, value: truncated ? String(value.prefix(CapturedHeader.valueLimit)) : value,
            truncated: truncated)
    }

    /// Every parameter in `query` (the raw query string, without `?`), in order, repeats included.
    public static func captureAll(
        _ query: String?, credentialNames: CredentialNames = .builtIn
    ) -> [CapturedQueryParameter] {
        guard let query, !query.isEmpty else { return [] }
        return query.split(separator: "&").compactMap { pair in
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = decode(parts[0])
            guard !name.isEmpty else { return nil }
            return capture(
                name: name, value: parts.count == 2 ? decode(parts[1]) : "", credentialNames: credentialNames)
        }
    }

    private static func decode(_ component: Substring) -> String {
        let formEncoded = String(component).replacingOccurrences(of: "+", with: " ")
        return formEncoded.removingPercentEncoding ?? formEncoded
    }
}
