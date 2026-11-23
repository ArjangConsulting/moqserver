import Foundation

/// A request body recorded by `serve --capture-request-bodies <bytes>`.
public struct MoqCapturedBody: Decodable, Equatable, Sendable {
    /// Text for UTF-8 bodies, base64 otherwise (see `encoding`).
    public let value: String
    /// `"utf8"` or `"base64"`.
    public let encoding: String
    /// Full body size in bytes, before truncation.
    public let size: Int
    public let truncated: Bool

    public init(value: String, encoding: String, size: Int, truncated: Bool) {
        self.value = value
        self.encoding = encoding
        self.size = size
        self.truncated = truncated
    }

    /// The body parsed as a JSON object, when it is complete UTF-8 JSON.
    public var jsonObject: [String: Any]? {
        guard encoding == "utf8", !truncated else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(value.utf8))) as? [String: Any]
    }
}

/// One request header recorded by `serve --capture-request-headers`. Credential headers carry no
/// `value`: only `scheme`, `fingerprint` and, for `cookie`, `cookieNames`.
public struct MoqCapturedHeader: Decodable, Equatable, Sendable {
    /// Lowercased header name.
    public let name: String
    public let value: String?
    public let truncated: Bool
    public let redacted: Bool
    /// Auth scheme of a redacted credential, e.g. `Bearer`.
    public let scheme: String?
    /// First 16 hex characters of the SHA-256 of the credential (after the scheme).
    public let fingerprint: String?
    public let cookieNames: [String]?

    public init(
        name: String, value: String? = nil, truncated: Bool = false, redacted: Bool = false, scheme: String? = nil,
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
}

/// Why the server refused a matched request itself. A mocked 4xx variant is not a rejection.
public struct MoqRequestRejection: Decodable, Equatable, Sendable {
    /// `auth`, `request_rule`, `session`, `call_count` or `no_variant`.
    public let kind: String
    /// The error code, e.g. `missing_required_header` or `unknown_session`.
    public let code: String
    /// What failed, e.g. `Header 'Authorization' is required`. Never contains a credential.
    public let message: String
    /// For `session` rejections: the `X-Mock-Session` value the request carried, if any.
    public let session: String?

    public init(kind: String, code: String, message: String, session: String? = nil) {
        self.kind = kind
        self.code = code
        self.message = message
        self.session = session
    }
}

/// One row of the server's request history (`GET /_admin/requests`).
public struct MoqRequestRecord: Decodable, Equatable, Sendable {
    public let id: String
    /// Unix seconds.
    public let timestamp: Double
    public let method: String
    public let path: String
    /// The matched endpoint (`"GET /users/{id}"`), or `nil` when no endpoint matched.
    public let endpoint: String?
    public let status: Int
    public let variant: String?
    /// Why this variant was chosen, or why the request was rejected.
    public let reason: String
    public let callNumber: Int?
    /// Add-on annotations keyed by add-on id, e.g. `["jwt-claims": ["sub": "user-1"]]`.
    public let addons: [String: [String: String]]?
    /// The request body, when the server captures bodies.
    public let requestBody: MoqCapturedBody?
    /// The request headers, when the server runs with `--capture-request-headers`.
    public let requestHeaders: [MoqCapturedHeader]?
    /// Set when the server refused the request itself.
    public let rejection: MoqRequestRejection?

    public init(
        id: String, timestamp: Double, method: String, path: String, endpoint: String?, status: Int,
        variant: String?, reason: String, callNumber: Int?, addons: [String: [String: String]]? = nil,
        requestBody: MoqCapturedBody? = nil, requestHeaders: [MoqCapturedHeader]? = nil,
        rejection: MoqRequestRejection? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.method = method
        self.path = path
        self.endpoint = endpoint
        self.status = status
        self.variant = variant
        self.reason = reason
        self.callNumber = callNumber
        self.addons = addons
        self.requestBody = requestBody
        self.requestHeaders = requestHeaders
        self.rejection = rejection
    }

    /// Whether the request hit a path no endpoint in the bundle serves.
    public var isUnmatched: Bool { endpoint == nil && rejection == nil }

    /// Whether the server refused the request itself (see ``rejection``).
    public var isRejected: Bool { rejection != nil }

    /// Token claims recorded by the `jwt-claims` add-on, e.g. `["sub": "u1", "iat": "1732000000"]`.
    public var claims: [String: String] { addons?["jwt-claims"] ?? [:] }

    /// The first captured header named `name` (any case).
    public func header(_ name: String) -> MoqCapturedHeader? {
        requestHeaders?.first { $0.name == name.lowercased() }
    }
}

/// Thrown by `assertNoUnmatchedRequests()`: the app called paths the bundle doesn't mock.
public struct MoqUnmatchedRequestsError: Error, Equatable, Sendable, CustomStringConvertible {
    public let requests: [MoqRequestRecord]

    public var description: String {
        let lines = requests.map { "  \($0.method) \($0.path)" }.joined(separator: "\n")
        return "App made \(requests.count) request(s) with no matching mock endpoint:\n\(lines)"
    }
}

extension MoqClient {
    /// Request history, newest first (at most 500 rows). Scoped to this client's session when it
    /// has one, so parallel suites never see each other's requests.
    public func requests() throws -> [MoqRequestRecord] {
        let data = try send("GET", url: baseURL.appendingPathComponent("_admin/requests"))
        return try JSONDecoder().decode([MoqRequestRecord].self, from: data)
    }

    /// Requests to paths no endpoint serves, oldest first. The server decides what counts
    /// (`POST /_admin/requests/query` with `unmatched: true`), so every client agrees.
    public func unmatchedRequests() throws -> [MoqRequestRecord] {
        try query(MoqRequestQuery(unmatched: true)).matched
    }

    /// Clears request history (this session's, when the client has one).
    public func clearRequests() throws {
        try send("DELETE", url: baseURL.appendingPathComponent("_admin/requests"))
    }

    /// Throws `MoqUnmatchedRequestsError` when the app called any path the bundle doesn't mock.
    /// Call at the end of a test (e.g. in `tearDown`) so a missing fixture fails loudly instead of
    /// the app silently tolerating a 404.
    public func assertNoUnmatchedRequests() throws {
        let unmatched = try unmatchedRequests()
        if !unmatched.isEmpty { throw MoqUnmatchedRequestsError(requests: unmatched) }
    }
}
