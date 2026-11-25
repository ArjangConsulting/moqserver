import Vapor

/// What `GET /_admin/version` reports: the release this binary was built from, when it was
/// stamped, and the admin-API capabilities it supports.
public struct ServerInfo: Content, Equatable, Sendable {
    /// The release tag (bare SemVer), or `nil` for a build from source.
    public let version: String?
    /// The commit a release was built from, or `nil` for a build from source.
    public let commit: String?
    /// Behaviours a client can rely on, by name. See ``ServerCapability``.
    public let capabilities: [String]

    public static let current = ServerInfo(
        version: BuildStamp.version, commit: BuildStamp.commit, capabilities: ServerCapability.allCases.map(\.rawValue))

    /// The text `moqserver --version` prints.
    public static var versionDescription: String {
        guard let version = current.version else { return "unreleased build" }
        guard let commit = current.commit else { return version }
        return "\(version) (\(commit.prefix(12)))"
    }
}

/// Admin-API behaviours a client may need to check for before relying on them.
///
/// Names are permanent: a capability is never renamed, and is removed only together with the
/// behaviour it names. Add a case whenever a route, field or behaviour lands that a client could
/// depend on, and list it in docs/ADMIN_API.md.
public enum ServerCapability: String, CaseIterable, Sendable {
    /// `serve --capture-request-headers` records redacted request headers on history rows.
    case requestHeaders = "request-headers"
    /// History rows carry `rejection` when the server refused a request itself.
    case requestRejections = "request-rejections"
    /// `POST /_admin/requests/query` and `POST /_admin/requests/mark`, including the `unmatched`
    /// filter and the 400 for an unknown `endpoint` key.
    case requestQuery = "request-query"
    /// `PUT /_admin/endpoints/:method/**/variant` accepts `times` for an override that clears itself.
    case limitedOverrides = "limited-overrides"
    /// `serve --capture-request-query` records query parameters on history rows (`requestQuery`), and
    /// request queries accept `query` predicates.
    case requestQueryParameters = "request-query-parameters"
}
