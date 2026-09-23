import Foundation

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

    public init(
        id: String, timestamp: Double, method: String, path: String, endpoint: String?, status: Int,
        variant: String?, reason: String, callNumber: Int?, addons: [String: [String: String]]? = nil
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
    }

    /// Whether the request hit a path no endpoint in the bundle serves.
    public var isUnmatched: Bool { endpoint == nil }
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

    /// Requests that matched no endpoint, oldest first.
    public func unmatchedRequests() throws -> [MoqRequestRecord] {
        try requests().filter(\.isUnmatched).reversed()
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
