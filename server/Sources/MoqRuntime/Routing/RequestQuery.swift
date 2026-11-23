import Foundation
import MoqCore

/// A question about request history, answered by `POST /_admin/requests/query`. The Swift and Kotlin
/// test clients and any other tool send this instead of filtering `GET /_admin/requests` themselves,
/// so every caller evaluates the same predicates the same way.
///
/// Rows are first narrowed by ``scope``, ``since``, ``endpoint``, ``method`` and ``path``. Those are
/// the candidates. A candidate matches when it also passes ``rejected``, ``headers`` and ``claims``;
/// candidates that fail are reported as near misses with the predicates they failed.
public struct RequestQuery: Codable, Sendable, Equatable {
    public enum Scope: String, Codable, Sendable {
        /// The caller's history: its session's when it sends `X-Mock-Session`, else the global one.
        case session
        /// Global-history rows recorded at or after the caller's session was created: requests that
        /// reached the server without `X-Mock-Session` while the caller's test ran. Needs
        /// `X-Mock-Session`.
        case sessionless
    }

    /// A matched endpoint key such as `GET /v1/users/{id}`. Method compares case-insensitively.
    public var endpoint: String?
    public var method: String?
    /// A concrete request path such as `/v1/users/42`.
    public var path: String?
    /// Unix seconds; only rows recorded at or after this time.
    public var since: Double?
    /// `true` keeps only rows the server rejected itself, `false` only rows it served.
    public var rejected: Bool?
    /// `true` keeps only requests to a path no endpoint serves (no `endpoint` and no `rejection`),
    /// `false` only the others. The one definition every client's `assertNoUnmatchedRequests` uses.
    public var unmatched: Bool?
    public var headers: [HeaderPredicate]?
    /// Each claim must equal the `jwt-claims` add-on's annotation on the row.
    public var claims: [String: String]?
    /// Exact number of matches expected; `nil` means at least one.
    public var times: Int?
    public var scope: Scope?
    /// With ``Scope/sessionless`` and ``claims``: also count rows that carry no token claims at all.
    /// They are listed in `unattributed` either way.
    public var includeUnattributed: Bool?
    /// With ``Scope/sessionless``: also count rows that carried another, already released or
    /// expired session's id (`released_session`), e.g. a finished run's app still running in the
    /// background. They are listed in `otherSessions` either way.
    public var includeOtherSessions: Bool?

    public init(
        endpoint: String? = nil, method: String? = nil, path: String? = nil, since: Double? = nil,
        rejected: Bool? = nil, unmatched: Bool? = nil, headers: [HeaderPredicate]? = nil,
        claims: [String: String]? = nil, times: Int? = nil, scope: Scope? = nil, includeUnattributed: Bool? = nil,
        includeOtherSessions: Bool? = nil
    ) {
        self.endpoint = endpoint
        self.method = method
        self.path = path
        self.since = since
        self.rejected = rejected
        self.unmatched = unmatched
        self.headers = headers
        self.claims = claims
        self.times = times
        self.scope = scope
        self.includeUnattributed = includeUnattributed
        self.includeOtherSessions = includeOtherSessions
    }
}

/// One header condition. Every field that is set must hold for the same header occurrence.
/// `present: false` alone means no header with this name was sent.
public struct HeaderPredicate: Codable, Sendable, Equatable {
    public var name: String
    public var present: Bool?
    /// Auth scheme, compared case-insensitively, e.g. `Bearer`.
    public var scheme: String?
    /// The recorded fingerprint (16 hex characters).
    public var fingerprint: String?
    /// A credential the server fingerprints and compares, e.g. the expected token without `Bearer `.
    public var fingerprintOf: String?
    /// Exact value; only for headers that are not credentials, since those store no value.
    public var equals: String?

    public init(
        name: String, present: Bool? = nil, scheme: String? = nil, fingerprint: String? = nil,
        fingerprintOf: String? = nil, equals: String? = nil
    ) {
        self.name = name
        self.present = present
        self.scheme = scheme
        self.fingerprint = fingerprint
        self.fingerprintOf = fingerprintOf
        self.equals = equals
    }

    var label: String {
        let lowered = name.lowercased()
        if present == false { return "no \(lowered) header" }
        var parts: [String] = []
        if let scheme { parts.append("scheme \(scheme)") }
        if let fingerprint { parts.append("fingerprint \(fingerprint)") }
        if fingerprintOf != nil { parts.append("fingerprint of the given credential") }
        if let equals { parts.append("= '\(equals)'") }
        return parts.isEmpty ? "\(lowered) header" : "\(lowered) " + parts.joined(separator: ", ")
    }
}

public struct RequestQueryNearMiss: Codable, Sendable {
    public let request: RequestTrace
    /// Labels of the predicates this row failed, e.g. `authorization scheme Bearer`.
    public let failed: [String]
}

public struct RequestQueryResult: Codable, Sendable {
    /// Whether the match count meets ``RequestQuery/times`` (at least one when `times` is `nil`).
    public let ok: Bool
    public let count: Int
    public let expected: Int?
    /// Matching rows, oldest first, so callers can compare consecutive calls (e.g. a retry's token).
    public let matched: [RequestTrace]
    /// Up to 10 candidates that failed a predicate, oldest first.
    public let nearMisses: [RequestQueryNearMiss]
    /// Session-less rows with no token claims, when the query filtered by claims.
    public let unattributed: [RequestTrace]
    /// Session-less rows that carried another, released session's id, left out unless
    /// ``RequestQuery/includeOtherSessions``.
    public let otherSessions: [RequestTrace]
    /// A readable verdict, suitable as an assertion failure message.
    public let message: String
}

public enum RequestQueryError: Error, Equatable, CustomStringConvertible {
    case negativeTimes
    case emptyHeaderName
    case valueOfCredential(String)
    case headerCaptureDisabled
    case sessionlessNeedsSession
    case malformedEndpoint(String)
    case operationEndpoint(String)
    case unknownEndpoint(String, suggestion: String?)
    case unknownMethod(String)
    case relativePath(String)

    public var description: String {
        switch self {
        case .negativeTimes: return "times must be 0 or more"
        case .emptyHeaderName: return "Every header predicate needs a name"
        case .valueOfCredential(let name):
            return
                "'\(name)' is a credential header and stores no value; use present, scheme, fingerprint or fingerprintOf"
        case .headerCaptureDisabled:
            return "Header predicates need the server to run with --capture-request-headers"
        case .sessionlessNeedsSession:
            return "scope sessionless needs X-Mock-Session: it reports global requests made since that session began"
        case .malformedEndpoint(let endpoint):
            return "endpoint '\(endpoint)' must be 'METHOD /path', e.g. 'GET /v1/users/{id}'"
        case .operationEndpoint(let endpoint):
            return
                "endpoint '\(endpoint)' names a GraphQL operation; history records the route, so query 'POST /graphql'"
        case .unknownEndpoint(let endpoint, let suggestion):
            let hint = suggestion.map { ". Did you mean '\($0)'?" } ?? ". List keys with GET /_admin/endpoints."
            return "No loaded endpoint is '\(endpoint)', so the query could never match\(hint)"
        case .unknownMethod(let method):
            return "'\(method)' is not an HTTP method"
        case .relativePath(let path):
            return "path '\(path)' must start with '/'"
        }
    }
}

extension RequestQuery {
    static let nearMissLimit = 10
    static let httpMethods: Set<String> = [
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT",
    ]

    /// Checks `endpoint`, `method` and `path` against what can ever be recorded, so a typo fails the
    /// query instead of making a `times: 0` check pass vacuously. An endpoint that differs from a
    /// loaded key only by a trailing slash resolves to that key.
    ///
    /// `routeKeys` are the loaded `METHOD /template` keys, as history records them.
    func resolvingFilters(routeKeys: Set<String>) throws -> RequestQuery {
        if let method, !Self.httpMethods.contains(method.uppercased()) { throw RequestQueryError.unknownMethod(method) }
        if let path, !path.hasPrefix("/") { throw RequestQueryError.relativePath(path) }
        guard let endpoint else { return self }
        let parts = endpoint.trimmingCharacters(in: .whitespaces).split(separator: " ", maxSplits: 1)
        guard parts.count == 2, parts[1].hasPrefix("/") else { throw RequestQueryError.malformedEndpoint(endpoint) }
        if parts[1].contains("#") { throw RequestQueryError.operationEndpoint(endpoint) }
        let method = parts[0].uppercased()
        let path = String(parts[1])
        let alternate = path.count > 1 && path.hasSuffix("/") ? String(path.dropLast()) : path + "/"
        var resolved = self
        for candidate in ["\(method) \(path)", "\(method) \(alternate)"] where routeKeys.contains(candidate) {
            resolved.endpoint = candidate
            return resolved
        }
        throw RequestQueryError.unknownEndpoint(
            endpoint, suggestion: Self.closest(to: "\(method) \(path)", in: routeKeys))
    }

    /// The loaded key nearest to `key` by edit distance, when it is plausibly a typo of it.
    static func closest(to key: String, in keys: Set<String>) -> String? {
        let scored = keys.map { ($0, editDistance(key, $0)) }.min { ($0.1, $0.0) < ($1.1, $1.0) }
        guard let (best, distance) = scored, distance <= max(3, key.count / 4) else { return nil }
        return best
    }

    static func editDistance(_ lhs: String, _ rhs: String) -> Int {
        let left = Array(lhs)
        let right = Array(rhs)
        if left.isEmpty { return right.count }
        if right.isEmpty { return left.count }
        var previous = Array(0...right.count)
        for (i, leftChar) in left.enumerated() {
            var current = [i + 1] + Array(repeating: 0, count: right.count)
            for (j, rightChar) in right.enumerated() {
                current[j + 1] = min(previous[j + 1] + 1, current[j] + 1, previous[j] + (leftChar == rightChar ? 0 : 1))
            }
            previous = current
        }
        return previous[right.count]
    }

    /// Checks the query before evaluation, so a predicate that cannot be answered fails loudly
    /// instead of silently passing.
    func validate(headerCaptureEnabled: Bool, redactHeaders: Set<String>) throws {
        if let times, times < 0 { throw RequestQueryError.negativeTimes }
        for predicate in headers ?? [] {
            let name = predicate.name.trimmingCharacters(in: .whitespaces)
            if name.isEmpty { throw RequestQueryError.emptyHeaderName }
            if predicate.equals != nil, SensitiveHeaders.isCredentialName(name, additionalNames: redactHeaders) {
                throw RequestQueryError.valueOfCredential(name.lowercased())
            }
        }
        if !(headers ?? []).isEmpty, !headerCaptureEnabled { throw RequestQueryError.headerCaptureDisabled }
    }

    /// Evaluates the query over `rows`, which must be oldest first. `callerSession` is the caller's
    /// `X-Mock-Session`, used by ``Scope/sessionless`` to recognise other sessions' leftovers.
    func evaluate(_ rows: [RequestTrace], callerSession: String? = nil) -> RequestQueryResult {
        var otherSessions: [RequestTrace] = []
        let candidates = rows.filter(isCandidate).filter { row in
            guard scope == .sessionless, let rejection = row.rejection, rejection.code == "released_session",
                rejection.session != callerSession
            else { return true }
            otherSessions.append(row)
            return includeOtherSessions == true
        }
        let filtersClaims = !(claims ?? [:]).isEmpty
        let countsUnattributed = scope == .sessionless && filtersClaims && includeUnattributed == true
        var matched: [RequestTrace] = []
        var unattributed: [RequestTrace] = []
        var nearMisses: [RequestQueryNearMiss] = []
        for row in candidates {
            let rowClaims = row.addons?["jwt-claims"] ?? [:]
            if scope == .sessionless, filtersClaims, rowClaims.isEmpty {
                unattributed.append(row)
                if countsUnattributed, failures(of: row, checkClaims: false).isEmpty { matched.append(row) }
                continue
            }
            let failed = failures(of: row, checkClaims: true)
            if failed.isEmpty {
                matched.append(row)
            } else if nearMisses.count < Self.nearMissLimit {
                nearMisses.append(RequestQueryNearMiss(request: row, failed: failed))
            }
        }
        let ok = times.map { matched.count == $0 } ?? !matched.isEmpty
        return RequestQueryResult(
            ok: ok, count: matched.count, expected: times, matched: matched, nearMisses: nearMisses,
            unattributed: filtersClaims && scope == .sessionless ? unattributed : [],
            otherSessions: otherSessions,
            message: message(
                ok: ok, matched: matched, nearMisses: nearMisses, unattributed: unattributed,
                otherSessions: otherSessions))
    }

    private func isCandidate(_ row: RequestTrace) -> Bool {
        if let since, row.timestamp < since { return false }
        if let method, row.method.caseInsensitiveCompare(method) != .orderedSame { return false }
        if let path, row.path != path { return false }
        if let endpoint {
            guard let rowEndpoint = row.endpoint, Self.sameEndpoint(rowEndpoint, endpoint) else { return false }
        }
        return true
    }

    private static func sameEndpoint(_ lhs: String, _ rhs: String) -> Bool {
        let left = lhs.split(separator: " ", maxSplits: 1)
        let right = rhs.trimmingCharacters(in: .whitespaces).split(separator: " ", maxSplits: 1)
        guard left.count == 2, right.count == 2 else { return lhs == rhs }
        return left[0].caseInsensitiveCompare(right[0]) == .orderedSame && left[1] == right[1]
    }

    private func failures(of row: RequestTrace, checkClaims: Bool) -> [String] {
        var failed: [String] = []
        if let rejected, rejected != (row.rejection != nil) {
            failed.append(rejected ? "rejected" : "not rejected")
        }
        if let unmatched, unmatched != row.isUnmatched {
            failed.append(unmatched ? "unmatched" : "matched")
        }
        for predicate in headers ?? [] where !Self.holds(predicate, in: row.requestHeaders ?? []) {
            failed.append(predicate.label)
        }
        if checkClaims {
            let rowClaims = row.addons?["jwt-claims"] ?? [:]
            for (name, value) in (claims ?? [:]).sorted(by: { $0.key < $1.key }) where rowClaims[name] != value {
                failed.append("claim \(name)=\(value)")
            }
        }
        return failed
    }

    private static func holds(_ predicate: HeaderPredicate, in headers: [CapturedHeader]) -> Bool {
        let name = predicate.name.trimmingCharacters(in: .whitespaces).lowercased()
        let sent = headers.filter { $0.name == name }
        if predicate.present == false { return sent.isEmpty }
        let expectedFingerprint = predicate.fingerprintOf.map(CapturedHeader.fingerprint(of:))
        return sent.contains { header in
            if let scheme = predicate.scheme,
                header.scheme?.caseInsensitiveCompare(scheme) != .orderedSame
            {
                return false
            }
            if let fingerprint = predicate.fingerprint, header.fingerprint != fingerprint.lowercased() { return false }
            if let expectedFingerprint, header.fingerprint != expectedFingerprint { return false }
            if let equals = predicate.equals, header.redacted || header.value != equals { return false }
            return true
        }
    }

    private func message(
        ok: Bool, matched: [RequestTrace], nearMisses: [RequestQueryNearMiss], unattributed: [RequestTrace],
        otherSessions: [RequestTrace]
    ) -> String {
        let count = matched.count
        var subject = "request(s)"
        if let endpoint {
            subject += " to \(endpoint)"
        } else if method != nil || path != nil {
            subject += " to \([method?.uppercased(), path].compactMap { $0 }.joined(separator: " "))"
        }
        if scope == .sessionless { subject = "session-less " + subject }
        var conditions: [String] = []
        if let rejected { conditions.append(rejected ? "rejected" : "not rejected") }
        if let unmatched { conditions.append(unmatched ? "unmatched" : "matched") }
        conditions += (headers ?? []).map(\.label)
        conditions += (claims ?? [:]).sorted(by: { $0.key < $1.key }).map { "claim \($0.key)=\($0.value)" }
        let matching = conditions.isEmpty ? "" : " matching [\(conditions.joined(separator: ", "))]"
        let window = since.map { " since \($0)" } ?? ""
        let expectation = times.map { "exactly \($0)" } ?? "at least 1"

        let othersNote =
            otherSessions.isEmpty || includeOtherSessions == true
            ? ""
            : " \(otherSessions.count) request(s) carrying another released session's id were not counted."
        guard !ok else {
            return "Found \(count) \(subject)\(matching)\(window), expected \(expectation).\(othersNote)"
        }
        var lines = ["Expected \(expectation) \(subject)\(matching)\(window), found \(count).\(othersNote)"]
        if let times, count > times {
            lines.append("Matched:")
            lines += matched.prefix(Self.nearMissLimit).map { "  \(Self.describe($0))" }
        }
        if !nearMisses.isEmpty {
            lines.append("Near misses:")
            lines += nearMisses.map { miss in
                "  \(Self.describe(miss.request)): failed \(miss.failed.joined(separator: ", "))"
            }
        }
        if !unattributed.isEmpty {
            lines.append(
                "Session-less requests with no token claims (not counted\(includeUnattributed == true ? "" : " unless includeUnattributed")):"
            )
            lines += unattributed.prefix(Self.nearMissLimit).map { "  \(Self.describe($0))" }
        }
        return lines.joined(separator: "\n")
    }

    static func describe(_ row: RequestTrace) -> String {
        var text = "\(row.method) \(row.path) → \(row.status)"
        if let rejection = row.rejection {
            text += " (\(rejection.kind.rawValue) \(rejection.code): \(rejection.message))"
        }
        return text
    }
}
