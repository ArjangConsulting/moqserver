import Foundation
import MoqCore

public struct RuntimeScenario: Codable, Sendable {
    public let name: String
    public let overrides: [String: String]

    public init(name: String, overrides: [String: String]) {
        self.name = name
        self.overrides = overrides
    }
}

public struct RequestTrace: Codable, Sendable {
    public let id: String
    public let timestamp: Double
    public let method: String
    public let path: String
    public let endpoint: String?
    public let status: Int
    public let variant: String?
    public let reason: String
    public let callNumber: Int?
    /// Add-on annotations (H7), keyed by add-on id — e.g. `{"jwt-claims": {"sub": "user-1"}}`.
    public var addons: [String: [String: String]]? = nil
    /// The request body, captured only with `serve --capture-request-bodies <bytes>`.
    public var requestBody: CapturedBody? = nil
    /// The request headers, captured only with `serve --capture-request-headers`. Credential
    /// headers never carry their value; see ``CapturedHeader``.
    public var requestHeaders: [CapturedHeader]? = nil
    /// Why the server refused to serve a mock response, when it did. `nil` for served requests and
    /// for requests no endpoint matched (those have `endpoint == nil`).
    public var rejection: RequestRejection? = nil

    /// A request to a path no endpoint serves: no matched endpoint and not refused by the server.
    public var isUnmatched: Bool { endpoint == nil && rejection == nil }
}

/// Why a matched request got an error from the server itself instead of a mocked variant. A mocked
/// 4xx variant is not a rejection.
public struct RequestRejection: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        /// The endpoint's auth requirement failed.
        case auth
        /// A `request_rules` header, query or cookie rule, the body requirement, or content type failed.
        case requestRule = "request_rule"
        /// `--require-session` is on and the request had no `X-Mock-Session`.
        case session
        /// `strict_call_count` is on and no variant covers this call number.
        case callCount = "call_count"
        /// No variant matched the request.
        case noVariant = "no_variant"
    }

    public let kind: Kind
    /// The machine-readable error code the response carried, e.g. `missing_required_header`.
    public let code: String
    /// What failed, e.g. `Header 'Authorization' is required`. Never contains a credential value.
    public let message: String

    public init(kind: Kind, code: String, message: String) {
        self.kind = kind
        self.code = code
        self.message = message
    }
}

/// A request body recorded in history, cut to the configured byte limit.
public struct CapturedBody: Codable, Sendable, Equatable {
    public enum Encoding: String, Codable, Sendable {
        case utf8
        case base64
    }

    /// The captured bytes: text for UTF-8 bodies, base64 otherwise.
    public let value: String
    public let encoding: Encoding
    /// The full body size in bytes, before truncation.
    public let size: Int
    public let truncated: Bool

    public init(value: String, encoding: Encoding, size: Int, truncated: Bool) {
        self.value = value
        self.encoding = encoding
        self.size = size
        self.truncated = truncated
    }

    /// Captures at most `limit` bytes of `data`. A cut inside a UTF-8 sequence backs off to the
    /// previous character boundary so text stays text; non-UTF-8 bodies are base64-encoded.
    public static func capture(_ data: Data, limit: Int) -> CapturedBody {
        let truncated = data.count > limit
        var prefix = data.prefix(limit)
        if let text = String(data: prefix, encoding: .utf8) {
            return CapturedBody(value: text, encoding: .utf8, size: data.count, truncated: truncated)
        }
        if truncated {
            for _ in 0..<3 where !prefix.isEmpty {
                prefix = prefix.dropLast()
                if let text = String(data: prefix, encoding: .utf8) {
                    return CapturedBody(value: text, encoding: .utf8, size: data.count, truncated: true)
                }
            }
        }
        return CapturedBody(
            value: data.prefix(limit).base64EncodedString(), encoding: .base64, size: data.count, truncated: truncated)
    }
}

/// A runtime session as listed by `GET /_admin/sessions`. Times are Unix seconds.
public struct RuntimeSessionInfo: Codable, Sendable, Equatable {
    public let id: String
    public let createdAt: Double
    public var lastUsedAt: Double
    /// When the session expires if left unused; `nil` when expiry is disabled.
    public var expiresAt: Double?

    public init(id: String, createdAt: Double, lastUsedAt: Double, expiresAt: Double? = nil) {
        self.id = id
        self.createdAt = createdAt
        self.lastUsedAt = lastUsedAt
        self.expiresAt = expiresAt
    }
}

public enum RuntimeScenarioError: Error, Sendable {
    case invalidName
    case invalidOverride(String)
    case notFound
    case capacityExceeded
}

extension InMemoryMockStore {
    public func defineScenario(_ scenario: RuntimeScenario) throws {
        guard !scenario.name.isEmpty, scenario.name.count <= 100 else { throw RuntimeScenarioError.invalidName }
        guard scenarios[scenario.name] != nil || scenarios.count < 100 else {
            throw RuntimeScenarioError.capacityExceeded
        }
        _ = try validatedOverrides(scenario.overrides)
        scenarios[scenario.name] = scenario
    }

    public func removeScenario(_ name: String) { scenarios.removeValue(forKey: name) }

    public func allScenarios() -> [RuntimeScenario] { scenarios.values.sorted { $0.name < $1.name } }

    public func activateScenario(_ name: String) throws {
        guard let scenario = scenarios[name] else { throw RuntimeScenarioError.notFound }
        let validated = try validatedOverrides(scenario.overrides)
        // No suspension: requests observe either the old or new overrides and counters together.
        variantOverrides = validated
        limitedOverrideUses.removeAll()
        callCounts.removeAll()
        activeScenario = name
        persistVariantOverridesIfNeeded()
    }

    public func resetRuntimeState(for key: String? = nil) {
        if let key {
            variantOverrides.removeValue(forKey: key)
            limitedOverrideUses.removeValue(forKey: key)
            callCounts.removeValue(forKey: key)
        } else {
            variantOverrides.removeAll()
            limitedOverrideUses.removeAll()
            callCounts.removeAll()
        }
        activeScenario = nil
        persistVariantOverridesIfNeeded()
    }

    /// Counts a request that reached variant selection and returns the override it should use,
    /// spending one use of a limited override.
    public func beginRequest(for key: String) -> (count: Int, override: String?) {
        let override = variantOverrides[key]
        if let use = limitedOverrideUses[key] {
            if override != use.variant {
                limitedOverrideUses.removeValue(forKey: key)
            } else if use.remaining <= 1 {
                limitedOverrideUses.removeValue(forKey: key)
                variantOverrides.removeValue(forKey: key)
                persistVariantOverridesIfNeeded()
            } else {
                limitedOverrideUses[key] = (use.variant, use.remaining - 1)
            }
        }
        return (incrementCallCount(for: key), override)
    }

    public func recordRequest(_ trace: RequestTrace) {
        requestHistory.append(trace)
        if requestHistory.count > 500 { requestHistory.removeFirst(requestHistory.count - 500) }
    }

    public func recentRequests() -> [RequestTrace] { requestHistory.reversed() }
    public func clearRequestHistory() { requestHistory.removeAll() }

    /// Default idle timeout for runtime sessions: 30 minutes without a request or admin call.
    public static let defaultSessionIdleTimeout: TimeInterval = 30 * 60
    /// Upper bound on live runtime sessions.
    public static let maxRuntimeSessions = 64

    /// Sets how long an unused session lives; `nil` (or a non-positive value) disables expiry.
    public func configureSessionExpiry(idleTimeout: TimeInterval?) {
        sessionIdleTimeout = idleTimeout.flatMap { $0 > 0 ? $0 : nil }
    }

    /// Replaces the clock used for session expiry (tests only).
    func setClock(_ clock: @escaping @Sendable () -> Date) { now = clock }

    public func createRuntimeSession() throws -> String {
        pruneExpiredRuntimeSessions()
        guard runtimeSessions.count < Self.maxRuntimeSessions else { throw RuntimeScenarioError.capacityExceeded }
        let id = UUID().uuidString
        runtimeSessions[id] = InMemoryMockStore(
            endpoints: endpoints, graphqlEndpoints: graphqlEndpoints, scenarios: scenarios)
        let created = now().timeIntervalSince1970
        runtimeSessionActivity[id] = RuntimeSessionInfo(id: id, createdAt: created, lastUsedAt: created)
        return id
    }

    /// Returns a live session and marks it used. An expired session is removed and reported missing.
    public func runtimeSession(_ id: String) -> InMemoryMockStore? {
        guard let session = runtimeSessions[id], var activity = runtimeSessionActivity[id] else { return nil }
        let current = now().timeIntervalSince1970
        if isExpired(activity, at: current) {
            removeRuntimeSession(id)
            return nil
        }
        activity.lastUsedAt = current
        runtimeSessionActivity[id] = activity
        return session
    }

    /// When a live session was created, without marking it used. `nil` for unknown or expired ids.
    public func runtimeSessionCreatedAt(_ id: String) -> Double? {
        guard let activity = runtimeSessionActivity[id], !isExpired(activity, at: now().timeIntervalSince1970) else {
            return nil
        }
        return activity.createdAt
    }

    public func removeRuntimeSession(_ id: String) {
        runtimeSessions.removeValue(forKey: id)
        runtimeSessionActivity.removeValue(forKey: id)
    }

    /// Live sessions, oldest first, with their expiry under the current idle timeout.
    public func listRuntimeSessions() -> [RuntimeSessionInfo] {
        pruneExpiredRuntimeSessions()
        return runtimeSessionActivity.values
            .map { info in
                var info = info
                info.expiresAt = sessionIdleTimeout.map { info.lastUsedAt + $0 }
                return info
            }
            .sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    private func pruneExpiredRuntimeSessions() {
        let current = now().timeIntervalSince1970
        for (id, activity) in runtimeSessionActivity where isExpired(activity, at: current) {
            removeRuntimeSession(id)
        }
    }

    private func isExpired(_ activity: RuntimeSessionInfo, at time: TimeInterval) -> Bool {
        guard let sessionIdleTimeout else { return false }
        return time - activity.lastUsedAt >= sessionIdleTimeout
    }

    private func validatedOverrides(_ overrides: [String: String]) throws -> [String: String] {
        var result: [String: String] = [:]
        for (key, name) in overrides {
            // GraphQL operations sharing a route are addressed as `POST /graphql#<operation>`.
            guard let endpoint = endpoint(forStateKey: key),
                let variant = endpoint.variants.first(where: { $0.matchesIdentifier(name) })
            else { throw RuntimeScenarioError.invalidOverride(key) }
            result[key] = variant.name
        }
        return result
    }
}
