import Foundation
import MoqCore

/// A compiled-in extension to the mock runtime, enabled per bundle under `addons:` in
/// `project.yml`.
///
/// Every hook payload is a plain `Codable` value — no Vapor type crosses this boundary — so an
/// out-of-process transport (JSON-RPC over stdio, like `moq-format`) can wrap the same protocol
/// later without changing it. Hooks, in request order:
///
/// - H1 `routes`: extra routes mounted under `/_addons/<id>/…`.
/// - H2 `enrich`: read-only per-request facts, computed once before auth and variant selection.
/// - H5 `matches`: a predicate contributed to a variant's `request_match.addons.<id>`.
/// - H7 `traceAnnotations`: string fields recorded on each request-history row.
/// - H8 `validateConfig` / `validateMatch`: static checks run by `moqserver validate` and at load.
///
/// An add-on must be stateless across requests, or keep any state per mock session, so parallel
/// sessions stay isolated.
public protocol MoqAddon: Sendable {
    /// Stable id used as the key under `addons:` and `request_match.addons`.
    static var id: String { get }

    /// H8: checks the add-on's `project.yml` config without a running server.
    static func validateConfig(_ config: AnyCodableValue) -> [AddonDiagnostic]

    /// H8: checks one `request_match.addons.<id>` spec without a running server.
    static func validateMatch(_ spec: AnyCodableValue) -> [AddonDiagnostic]

    /// Builds the add-on for serving. Throws `AddonActivationError` when the config or the
    /// environment (for example a non-loopback bind) does not allow it to run.
    init(config: AnyCodableValue, environment: AddonEnvironment) throws

    /// H1: routes mounted under `/_addons/<id>/`.
    var routes: [AddonRoute] { get }

    /// Extra path prefixes (as components) where `routes` are also mounted, for built-in features
    /// that moved into an add-on and must keep their old URLs (e.g. `oauth-mock` at `/_auth`).
    /// Only reserved paths belong here, so a bundle's endpoints can never collide with them.
    var compatibilityRoutePrefixes: [[String]] { get }

    /// H2: facts about the request, exposed to this add-on's own `matches` and
    /// `traceAnnotations`. `nil` means the add-on has nothing to say about this request.
    func enrich(_ request: AddonRequest) async -> AnyCodableValue?

    /// H5: whether the request, described by this add-on's `facts`, satisfies `spec`.
    func matches(_ spec: AnyCodableValue, facts: AnyCodableValue?) -> Bool

    /// H7: fields to record on the request-history row.
    func traceAnnotations(facts: AnyCodableValue?) -> [String: String]
}

extension MoqAddon {
    public static func validateMatch(_ spec: AnyCodableValue) -> [AddonDiagnostic] {
        [AddonDiagnostic(message: "Add-on \"\(id)\" does not provide request_match predicates.")]
    }

    public var routes: [AddonRoute] { [] }

    public var compatibilityRoutePrefixes: [[String]] { [] }

    public func enrich(_ request: AddonRequest) async -> AnyCodableValue? { nil }

    public func matches(_ spec: AnyCodableValue, facts: AnyCodableValue?) -> Bool { false }

    public func traceAnnotations(facts: AnyCodableValue?) -> [String: String] { [:] }
}

/// A problem found by an add-on's static validation. `field` is relative to the add-on's own
/// config or match spec (for example `verify_signature`); core prefixes the location.
public struct AddonDiagnostic: Sendable, Equatable {
    public let field: String?
    public let message: String

    public init(field: String? = nil, message: String) {
        self.field = field
        self.message = message
    }
}

/// Why an add-on refused to start.
public struct AddonActivationError: Error, Sendable, Equatable, CustomStringConvertible {
    public let addonID: String
    public let message: String

    public init(addonID: String, message: String) {
        self.addonID = addonID
        self.message = message
    }

    public var description: String { "Add-on \"\(addonID)\": \(message)" }
}

/// Facts about how the server was started that an add-on may need to decide whether it can run.
public struct AddonEnvironment: Sendable, Equatable {
    /// The address the server binds to.
    public let hostname: String
    /// Set by `serve --allow-unverified-jwt`: accept unsigned or unverified tokens even when the
    /// server is reachable from other machines.
    public let allowUnverifiedJWT: Bool

    public init(hostname: String = "127.0.0.1", allowUnverifiedJWT: Bool = false) {
        self.hostname = hostname
        self.allowUnverifiedJWT = allowUnverifiedJWT
    }

    private static let loopbackHostnames: Set<String> = ["127.0.0.1", "localhost", "::1"]

    /// Whether only this machine can reach the server.
    public var isLoopbackBind: Bool { Self.loopbackHostnames.contains(hostname.lowercased()) }
}

/// The request as H2 sees it. Header names are lowercased.
public struct AddonRequest: Codable, Sendable, Equatable {
    public let method: String
    public let path: String
    public let query: [String: String]
    public let headers: [String: String]
    /// The `X-Mock-Session` id, when the request is session-scoped.
    public let sessionID: String?

    public init(
        method: String, path: String, query: [String: String] = [:], headers: [String: String] = [:],
        sessionID: String? = nil
    ) {
        self.method = method
        self.path = path
        self.query = query
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { _, last in last })
        self.sessionID = sessionID
    }

    public func header(_ name: String) -> String? { headers[name.lowercased()] }
}

/// A request to an H1 route. `path` is relative to `/_addons/<id>`.
public struct AddonHTTPRequest: Codable, Sendable, Equatable {
    public let method: String
    public let path: String
    public let query: [String: String]
    public let headers: [String: String]
    public let body: Data?

    public init(
        method: String, path: String, query: [String: String] = [:], headers: [String: String] = [:],
        body: Data? = nil
    ) {
        self.method = method
        self.path = path
        self.query = query
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { _, last in last })
        self.body = body
    }

    public func header(_ name: String) -> String? { headers[name.lowercased()] }

    /// String parameters from an `application/x-www-form-urlencoded` or JSON-object body. JSON
    /// values that aren't strings are skipped; other content types yield no parameters.
    public func bodyParameters() -> [String: String] {
        guard let body, !body.isEmpty else { return [:] }
        let contentType = header("Content-Type")?.lowercased() ?? ""
        if contentType.hasPrefix("application/x-www-form-urlencoded") {
            var parameters: [String: String] = [:]
            for pair in String(decoding: body, as: UTF8.self).split(separator: "&") {
                let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard let rawName = parts.first else { continue }
                let decode: (Substring) -> String = {
                    let spaced = String($0).replacingOccurrences(of: "+", with: " ")
                    return spaced.removingPercentEncoding ?? spaced
                }
                parameters[decode(rawName)] = parts.count > 1 ? decode(parts[1]) : ""
            }
            return parameters
        }
        if contentType.hasPrefix("application/json"),
            case .object(let fields)? = try? JSONDecoder().decode(AnyCodableValue.self, from: body)
        {
            return fields.compactMapValues {
                if case .string(let value) = $0 { return value }
                return nil
            }
        }
        return [:]
    }
}

/// The response from an H1 route.
public struct AddonHTTPResponse: Codable, Sendable, Equatable {
    public let status: Int
    public let headers: [String: String]
    public let body: Data?

    public init(status: Int, headers: [String: String] = [:], body: Data? = nil) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    /// A JSON response with sorted keys.
    public static func json(_ value: AnyCodableValue, status: Int = 200) -> AddonHTTPResponse {
        AddonHTTPResponse(
            status: status, headers: ["Content-Type": "application/json"], body: value.toJSONData())
    }
}

/// An H1 route. `path` components are relative to `/_addons/<id>`; a component starting with
/// `:` is a parameter, matching Vapor's syntax.
public struct AddonRoute: Sendable {
    public let method: String
    public let path: [String]
    public let handler: @Sendable (AddonHTTPRequest) async -> AddonHTTPResponse

    public init(
        method: String, path: [String], handler: @escaping @Sendable (AddonHTTPRequest) async -> AddonHTTPResponse
    ) {
        self.method = method.uppercased()
        self.path = path
        self.handler = handler
    }
}
