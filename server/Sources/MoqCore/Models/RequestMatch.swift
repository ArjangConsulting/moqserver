import Foundation

/// Additional request matching criteria for selecting a variant.
public struct RequestMatch: Codable, Equatable, Sendable {
    public let query: [String: String]
    public let headers: [String: String]
    public let bodyContains: String?
    /// Predicates evaluated by add-ons, keyed by add-on id. Each spec is opaque to core; the
    /// named add-on validates it at load time and evaluates it per request.
    public let addons: [String: AnyCodableValue]

    enum CodingKeys: String, CodingKey {
        case query
        case headers
        case bodyContains = "body_contains"
        case addons
    }

    public init(
        query: [String: String] = [:],
        headers: [String: String] = [:],
        bodyContains: String? = nil,
        addons: [String: AnyCodableValue] = [:]
    ) {
        self.query = query
        self.headers = headers
        self.bodyContains = bodyContains
        self.addons = addons
    }

    /// Whether this match has no predicates at all.
    public var isEmpty: Bool {
        query.isEmpty && headers.isEmpty && (bodyContains?.isEmpty ?? true) && addons.isEmpty
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            query: try container.decodeIfPresent([String: String].self, forKey: .query) ?? [:],
            headers: try container.decodeIfPresent([String: String].self, forKey: .headers) ?? [:],
            bodyContains: try container.decodeIfPresent(String.self, forKey: .bodyContains),
            addons: try container.decodeIfPresent([String: AnyCodableValue].self, forKey: .addons) ?? [:]
        )
    }
}
