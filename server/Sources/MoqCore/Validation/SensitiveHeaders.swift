import Foundation

/// Decides which header values are credentials that must never be stored or echoed back. Request
/// history redacts them, validation messages hide them, and the HAR importer strips them.
///
/// A header counts as a credential when any of these holds:
/// - its name is in ``names`` (the Cloudflare HAR Sanitizer default word list);
/// - its name contains one of ``nameKeywords``, so custom auth headers such as
///   `X-Firebase-AppCheck` or `X-Refresh-Token` are caught without configuration;
/// - its name is in the caller's `additionalNames` (`--redact-header`);
/// - its value contains a JWT, whatever the header is called.
///
/// `X-Mock-Session` is never a credential: it selects test state and tests assert on it.
public enum SensitiveHeaders {
    /// Lowercased header names that are always credentials.
    public static let names: Set<String> = [
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-auth-token",
        "x-csrf-token",
        "x-forwarded-for",
        "proxy-authorization",
        "www-authenticate",
    ]

    /// Lowercased substrings that mark a header name as credential-bearing.
    public static let nameKeywords: [String] = [
        "auth", "token", "secret", "password", "passwd", "credential", "signature", "session", "cookie", "key",
        "appcheck",
    ]

    /// Header names that match a keyword but carry no secret.
    public static let exemptNames: Set<String> = [
        "x-mock-session", "x-mock-variant", "access-control-allow-credentials",
    ]

    /// Matches a JWT (header.payload.signature) anywhere in a value. The signature may be empty, as
    /// in the unsigned tokens the Firebase Auth emulator issues.
    public static let jwtPattern = #"(ey[A-Za-z0-9\-_=]+)\.(ey[A-Za-z0-9\-_=]+)\.[A-Za-z0-9\-_.+/=]*"#

    /// Whether `name` (any case) is on the fixed list. Ignores keywords, extra names and values.
    public static func contains(_ name: String) -> Bool {
        names.contains(name.lowercased())
    }

    /// Whether the header's name marks it as a credential: fixed list, keyword or `additionalNames`.
    public static func isCredentialName(_ name: String, additionalNames: Set<String> = []) -> Bool {
        let lowered = name.lowercased()
        if exemptNames.contains(lowered) { return false }
        if names.contains(lowered) || additionalNames.contains(lowered) { return true }
        return nameKeywords.contains { lowered.contains($0) }
    }

    /// Whether the header carries a credential, by name or because its value holds a JWT.
    /// `additionalNames` must be lowercased.
    public static func isCredential(name: String, value: String?, additionalNames: Set<String> = []) -> Bool {
        if isCredentialName(name, additionalNames: additionalNames) { return true }
        if exemptNames.contains(name.lowercased()) { return false }
        guard let value else { return false }
        return containsJWT(value)
    }

    /// Whether `value` contains a JWT.
    public static func containsJWT(_ value: String) -> Bool {
        value.range(of: jwtPattern, options: .regularExpression) != nil
    }

    /// Lowercases and trims user-supplied header names, dropping empty ones.
    public static func normalized(_ names: some Sequence<String>) -> Set<String> {
        Set(names.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty })
    }
}
