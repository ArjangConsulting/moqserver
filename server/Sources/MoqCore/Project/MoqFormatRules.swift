import Foundation

/// The `.moqproj` format constants shared by validation, writing, and MCP authoring resources.
///
/// This is the Swift-side single source of truth for values that Kotlin's
/// `studio-project-format` module currently duplicates by hand. Until Studio delegates to the
/// Swift core, keep `studio/studio-project-format/.../ProjectValidator.kt` in sync with these
/// values — the paired contract tests
/// (`server/Tests/MoqFormatTests/SharedRulesContractTests.swift` and
/// `studio/studio-project-format/src/jvmTest/.../SharedRulesContractTest.kt`) exist to catch
/// drift between the two in the meantime.
public enum MoqFormatRules {
    /// The only `.moqproj` manifest format version currently supported.
    public static let formatVersion = "1"

    /// Endpoint ids must be lowercase alphanumeric with hyphens, and may not start with a hyphen.
    public static let endpointIDPattern = "^[a-z0-9][a-z0-9-]*$"

    /// HTTP methods accepted on an endpoint. Intentionally excludes `TRACE` and `CONNECT`:
    /// both are absent from mock-serving semantics (no coherent proxy-tunnel or diagnostic-echo
    /// behavior to mock), and neither has ever been accepted by this validator.
    public static let supportedMethods: Set<String> = [
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS",
    ]

    /// Paths reserved for the server's own routes and unavailable to mock endpoints.
    private static let reservedExactPaths: Set<String> = ["/health", "/_admin", "/_auth"]
    private static let reservedPathPrefixes: [String] = ["/_admin/", "/_auth/"]

    public static func isValidEndpointID(_ id: String) -> Bool {
        id.range(of: endpointIDPattern, options: .regularExpression) != nil
    }

    public static func isSupportedMethod(_ method: String) -> Bool {
        supportedMethods.contains(method.uppercased())
    }

    public static func isReservedPath(_ path: String) -> Bool {
        reservedExactPaths.contains(path) || reservedPathPrefixes.contains { path.hasPrefix($0) }
    }
}
