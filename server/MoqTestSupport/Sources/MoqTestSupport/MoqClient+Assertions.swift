import Foundation

/// One condition on a request header, evaluated by the server (`POST /_admin/requests/query`).
/// Header predicates need the server to run with `--capture-request-headers`.
public enum MoqHeaderExpectation: Encodable, Equatable, Sendable {
    /// The header was sent.
    case present(String)
    /// The header was not sent.
    case absent(String)
    /// The header was sent with this auth scheme (case-insensitive), e.g. `.scheme("Authorization", "Bearer")`.
    case scheme(String, String)
    /// The header carried this credential, e.g. `.fingerprint("Authorization", of: idToken)` with the
    /// token alone, without `Bearer `. The server compares fingerprints; the value is never stored.
    case fingerprint(String, of: String)
    /// The header had exactly this value. Refused for credential headers, which store no value.
    case equals(String, String)

    private enum CodingKeys: String, CodingKey {
        case name, present, scheme, fingerprintOf, equals
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .present(let name):
            try container.encode(name, forKey: .name)
            try container.encode(true, forKey: .present)
        case .absent(let name):
            try container.encode(name, forKey: .name)
            try container.encode(false, forKey: .present)
        case .scheme(let name, let scheme):
            try container.encode(name, forKey: .name)
            try container.encode(scheme, forKey: .scheme)
        case .fingerprint(let name, let credential):
            try container.encode(name, forKey: .name)
            try container.encode(credential, forKey: .fingerprintOf)
        case .equals(let name, let value):
            try container.encode(name, forKey: .name)
            try container.encode(value, forKey: .equals)
        }
    }
}

/// A question about request history, answered by the server. Every field is optional; see
/// `docs/ADMIN_API.md` ("Request queries") for the exact semantics.
public struct MoqRequestQuery: Encodable, Equatable, Sendable {
    public enum Scope: String, Encodable, Sendable {
        /// This client's history: its session's, or the global one without a session.
        case session
        /// Global rows recorded since this client's session was created: requests that reached the
        /// server without a valid `X-Mock-Session` while the test ran.
        case sessionless
    }

    /// A matched endpoint key, e.g. `GET /v1/users/{id}`.
    public var endpoint: String?
    public var method: String?
    /// A concrete request path, e.g. `/v1/users/42`.
    public var path: String?
    /// Unix seconds from ``MoqClient/mark()``; only rows at or after it.
    public var since: Double?
    public var rejected: Bool?
    public var headers: [MoqHeaderExpectation]?
    public var claims: [String: String]?
    /// Exact match count expected; `nil` means at least one.
    public var times: Int?
    public var scope: Scope?
    public var includeUnattributed: Bool?

    public init(
        endpoint: String? = nil, method: String? = nil, path: String? = nil, since: Double? = nil,
        rejected: Bool? = nil, headers: [MoqHeaderExpectation]? = nil, claims: [String: String]? = nil,
        times: Int? = nil, scope: Scope? = nil, includeUnattributed: Bool? = nil
    ) {
        self.endpoint = endpoint
        self.method = method
        self.path = path
        self.since = since
        self.rejected = rejected
        self.headers = headers
        self.claims = claims
        self.times = times
        self.scope = scope
        self.includeUnattributed = includeUnattributed
    }
}

public struct MoqRequestNearMiss: Decodable, Equatable, Sendable {
    public let request: MoqRequestRecord
    /// The predicates this request failed, e.g. `authorization scheme Bearer`.
    public let failed: [String]
}

/// The server's verdict on a ``MoqRequestQuery``.
public struct MoqRequestQueryResult: Decodable, Equatable, Sendable {
    public let ok: Bool
    public let count: Int
    public let expected: Int?
    /// Matching requests, oldest first, so a test can compare a retry with the first call.
    public let matched: [MoqRequestRecord]
    public let nearMisses: [MoqRequestNearMiss]
    /// Session-less requests with no token claims, when a session check filtered by claims.
    public let unattributed: [MoqRequestRecord]
    /// A readable verdict, used as the assertion failure message.
    public let message: String
}

/// Thrown by the `assert…` request checks; `description` is the server's verdict.
public struct MoqRequestAssertionError: Error, Equatable, Sendable, CustomStringConvertible {
    public let result: MoqRequestQueryResult

    public var description: String { result.message }
}

extension MoqClient {
    var requestQueryURL: URL { baseURL.appendingPathComponent("_admin/requests/query") }
    var requestMarkURL: URL { baseURL.appendingPathComponent("_admin/requests/mark") }

    static func assertionQuery(
        endpoint: String, times: Int?, headers: [MoqHeaderExpectation], claims: [String: String], since: Double?
    ) -> MoqRequestQuery {
        MoqRequestQuery(
            endpoint: endpoint, since: since, headers: headers.isEmpty ? nil : headers,
            claims: claims.isEmpty ? nil : claims, times: times)
    }

    static func sessionQuery(claims: [String: String], includeUnattributed: Bool, since: Double?) -> MoqRequestQuery {
        MoqRequestQuery(
            since: since, claims: claims.isEmpty ? nil : claims, times: 0, scope: .sessionless,
            includeUnattributed: includeUnattributed ? true : nil)
    }

    /// Asks the server to evaluate `query` over this client's request history.
    public func query(_ query: MoqRequestQuery) throws -> MoqRequestQueryResult {
        try Self.decode(MoqRequestQueryResult.self, from: send("POST", url: requestQueryURL, json: Self.json(query)))
    }

    /// The server's current time, to pass as `since` so a check only sees later requests.
    public func mark() throws -> Double {
        let result = try Self.decode([String: Double].self, from: send("POST", url: requestMarkURL))
        guard let timestamp = result["timestamp"] else { throw MoqControlError.transport("Missing timestamp") }
        return timestamp
    }

    /// Re-runs `query` until it passes or `timeout` elapses (`0` checks once), then throws
    /// ``MoqRequestAssertionError`` with the server's verdict.
    func assertQuery(_ query: MoqRequestQuery, timeout: TimeInterval) throws -> MoqRequestQueryResult {
        let deadline = ProcessInfo.processInfo.systemUptime + max(0, timeout)
        while true {
            let result = try self.query(query)
            if result.ok { return result }
            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            guard remaining > 0 else { throw MoqRequestAssertionError(result: result) }
            Thread.sleep(forTimeInterval: min(0.1, remaining))
        }
    }

    /// Asserts that `endpoint` (e.g. `GET /v1/me`) was called `times` times (at least once when
    /// `nil`) with every header expectation and claim holding, and returns those calls oldest first.
    /// `timeout` waits for calls the app hasn't made yet.
    ///
    /// ```swift
    /// let calls = try client.assertRequest(
    ///     endpoint: "GET /v1/me", times: 2, headers: [.scheme("Authorization", "Bearer")], claims: ["sub": uid])
    /// XCTAssertLessThan(Int(calls[0].claims["iat"] ?? "") ?? 0, Int(calls[1].claims["iat"] ?? "") ?? 0)
    /// ```
    @discardableResult
    public func assertRequest(
        endpoint: String, times: Int? = nil, headers: [MoqHeaderExpectation] = [], claims: [String: String] = [:],
        since: Double? = nil, timeout: TimeInterval = 0
    ) throws -> [MoqRequestRecord] {
        try assertQuery(
            Self.assertionQuery(endpoint: endpoint, times: times, headers: headers, claims: claims, since: since),
            timeout: timeout
        ).matched
    }

    /// Requests the server refused itself (auth, request rules, session, call count), oldest first.
    public func rejectedRequests(since: Double? = nil) throws -> [MoqRequestRecord] {
        try query(MoqRequestQuery(since: since, rejected: true)).matched
    }

    /// Throws ``MoqRequestAssertionError`` naming each rejected request and the rule it broke.
    public func assertNoRejectedRequests(since: Double? = nil) throws {
        _ = try assertQuery(MoqRequestQuery(since: since, rejected: true, times: 0), timeout: 0)
    }

    /// Throws when a request reached the server without a valid `X-Mock-Session` since this client's
    /// session was created. Needs a session-scoped client (``createSession()``).
    ///
    /// With `claims` (e.g. `["sub": uid]`), only session-less requests carrying this test's token
    /// count, so parallel workers sharing one server don't fail each other. Requests with no token
    /// can't be attributed: they count only with `includeUnattributed`, and are returned either way.
    @discardableResult
    public func assertAllRequestsCarrySession(
        claims: [String: String] = [:], includeUnattributed: Bool = false, since: Double? = nil
    ) throws -> [MoqRequestRecord] {
        try assertQuery(
            Self.sessionQuery(claims: claims, includeUnattributed: includeUnattributed, since: since), timeout: 0
        ).unattributed
    }

    /// Makes `variant` the active response for the next `times` requests that reach variant
    /// selection, then returns the endpoint to normal selection. Use it to fail one call and let the
    /// app's retry succeed.
    public func selectVariant(
        _ variant: String, for method: String, path: String, operation: String? = nil, times: Int
    ) throws {
        try send(
            "PUT", url: adminURL(method: method, path: path, subresource: "variant", operation: operation),
            json: Self.json(MoqVariantSelection(variant: variant, times: times)))
    }
}

struct MoqVariantSelection: Encodable {
    let variant: String
    let times: Int?
}

extension MoqAsyncClient {
    public func query(_ query: MoqRequestQuery) async throws -> MoqRequestQueryResult {
        try MoqClient.decode(
            MoqRequestQueryResult.self,
            from: await client.sendAsync("POST", url: client.requestQueryURL, json: MoqClient.json(query)))
    }

    public func mark() async throws -> Double {
        let result = try MoqClient.decode(
            [String: Double].self, from: await client.sendAsync("POST", url: client.requestMarkURL))
        guard let timestamp = result["timestamp"] else { throw MoqControlError.transport("Missing timestamp") }
        return timestamp
    }

    func assertQuery(_ query: MoqRequestQuery, timeout: TimeInterval) async throws -> MoqRequestQueryResult {
        let deadline = ProcessInfo.processInfo.systemUptime + max(0, timeout)
        while true {
            let result = try await self.query(query)
            if result.ok { return result }
            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            guard remaining > 0 else { throw MoqRequestAssertionError(result: result) }
            try await Task.sleep(nanoseconds: UInt64(min(0.1, remaining) * 1_000_000_000))
        }
    }

    @discardableResult
    public func assertRequest(
        endpoint: String, times: Int? = nil, headers: [MoqHeaderExpectation] = [], claims: [String: String] = [:],
        since: Double? = nil, timeout: TimeInterval = 0
    ) async throws -> [MoqRequestRecord] {
        try await assertQuery(
            MoqClient.assertionQuery(endpoint: endpoint, times: times, headers: headers, claims: claims, since: since),
            timeout: timeout
        ).matched
    }

    public func rejectedRequests(since: Double? = nil) async throws -> [MoqRequestRecord] {
        try await query(MoqRequestQuery(since: since, rejected: true)).matched
    }

    public func assertNoRejectedRequests(since: Double? = nil) async throws {
        _ = try await assertQuery(MoqRequestQuery(since: since, rejected: true, times: 0), timeout: 0)
    }

    @discardableResult
    public func assertAllRequestsCarrySession(
        claims: [String: String] = [:], includeUnattributed: Bool = false, since: Double? = nil
    ) async throws -> [MoqRequestRecord] {
        try await assertQuery(
            MoqClient.sessionQuery(claims: claims, includeUnattributed: includeUnattributed, since: since),
            timeout: 0
        ).unattributed
    }

    public func selectVariant(
        _ variant: String, for method: String, path: String, operation: String? = nil, times: Int
    ) async throws {
        _ = try await client.sendAsync(
            "PUT", url: client.adminURL(method: method, path: path, subresource: "variant", operation: operation),
            json: MoqClient.json(MoqVariantSelection(variant: variant, times: times)))
    }
}
