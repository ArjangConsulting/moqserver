/// Request header names whose values are credentials and must never be stored or echoed back:
/// request history redacts them, validation messages hide them, and the HAR importer strips them.
/// Mirrors the Cloudflare HAR Sanitizer default word list. The Kotlin HAR importer in Studio keeps
/// its own copy of this list; change both together.
public enum SensitiveHeaders {
    /// Lowercased header names.
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

    /// Whether `name` (any case) is a credential-bearing header.
    public static func contains(_ name: String) -> Bool {
        names.contains(name.lowercased())
    }
}
