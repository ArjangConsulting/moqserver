import Foundation

/// One endpoint as listed by `GET /_admin/endpoints`.
public struct MoqEndpoint: Decodable, Equatable, Sendable {
    public let method: String
    public let path: String
    /// The key overrides, call counts and scenarios use: `"GET /users/{id}"`, or
    /// `"POST /graphql#GetUser"` for a GraphQL operation on a shared route.
    public let key: String
    /// GraphQL operation selector to pass as `operation:`; `nil` for REST endpoints.
    public let operation: String?
    public let variants: [String]
    public let activeVariant: String?
    public let currentCallCount: Int
}

/// A runtime scenario: endpoint key → variant name.
public struct MoqScenario: Codable, Equatable, Sendable {
    public let name: String
    public let overrides: [String: String]

    public init(name: String, overrides: [String: String]) {
        self.name = name
        self.overrides = overrides
    }
}

/// A live runtime session. Times are Unix seconds; `expiresAt` is `nil` when sessions never expire.
public struct MoqSession: Decodable, Equatable, Sendable {
    public let id: String
    public let createdAt: Double
    public let lastUsedAt: Double
    public let expiresAt: Double?
}

/// The result of reloading the bundle from disk.
public struct MoqReloadSummary: Decodable, Equatable, Sendable {
    public let endpoints: Int
    public let scenarios: Int
    public let sessions: Int
    /// Runtime state the new bundle could not keep.
    public let warnings: [String]
}

private struct EndpointCallCount: Decodable {
    let currentCallCount: Int
}

private struct ScenarioName: Encodable {
    let name: String
}

/// URLs and response decoding shared by the blocking and async clients.
extension MoqClient {
    var endpointsURL: URL { baseURL.appendingPathComponent("_admin/endpoints") }
    var scenariosURL: URL { baseURL.appendingPathComponent("_admin/scenarios") }
    var sessionsURL: URL { baseURL.appendingPathComponent("_admin/sessions") }
    var reloadURL: URL { baseURL.appendingPathComponent("_admin/reload") }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw MoqControlError.transport("Unexpected response from moqserver: \(error)")
        }
    }

    static func callCount(from data: Data) throws -> Int {
        try decode(EndpointCallCount.self, from: data).currentCallCount
    }

    static func scenarioName(_ name: String) throws -> Data {
        try json(ScenarioName(name: name))
    }
}

extension MoqClient {
    /// Every endpoint the server serves (this session's view when the client has one).
    public func endpoints() throws -> [MoqEndpoint] {
        try Self.decode([MoqEndpoint].self, from: send("GET", url: endpointsURL))
    }

    /// How many times `method path` (or one GraphQL `operation`) has been called since the last reset.
    public func callCount(for method: String, path: String, operation: String? = nil) throws -> Int {
        try Self.callCount(
            from: send("GET", url: adminURL(method: method, path: path, subresource: "", operation: operation)))
    }

    /// Scenarios defined in the bundle or at runtime.
    public func scenarios() throws -> [MoqScenario] {
        try Self.decode([MoqScenario].self, from: send("GET", url: scenariosURL))
    }

    /// Defines (or replaces) a runtime scenario. Keys are endpoint keys as listed by ``endpoints()``.
    public func defineScenario(_ name: String, overrides: [String: String]) throws {
        try send("PUT", url: scenariosURL, json: Self.json(MoqScenario(name: name, overrides: overrides)))
    }

    public func removeScenario(_ name: String) throws {
        try send("DELETE", url: scenariosURL, json: Self.scenarioName(name))
    }

    /// Live sessions on the server, oldest first.
    public func sessions() throws -> [MoqSession] {
        try Self.decode([MoqSession].self, from: send("GET", url: sessionsURL))
    }

    /// Re-reads the bundle from disk; the server keeps the previous version if the new one is invalid.
    @discardableResult
    public func reload() throws -> MoqReloadSummary {
        try Self.decode(MoqReloadSummary.self, from: send("POST", url: reloadURL))
    }
}
