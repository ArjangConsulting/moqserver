/// An endpoint definition in the .moqproj format, representing one YAML file in endpoints/.
public struct EndpointDocument: Codable, Sendable, Equatable {
    /// Unique endpoint identifier (lowercase alphanumeric + hyphens).
    public let id: String
    /// Human-readable display name.
    public let alias: String?
    /// Optional human-readable description.
    public let description: String?
    /// Stable code-friendly identifier used by Studio and automation.
    public let referenceName: String
    /// HTTP method (GET, POST, etc.).
    public let method: String
    /// URL path pattern (must start with /).
    public let path: String
    /// Tags for grouping/filtering.
    public let tags: [String]?
    /// Auth configuration override (falls back to project defaults if nil).
    public let auth: ProjectAuthConfig?
    /// Request validation rules.
    public let requestRules: RequestRules?
    /// GraphQL operation matching (required when path is /graphql).
    public let operation: EndpointOperation?
    /// Network simulation override.
    public let network: NetworkBehavior?
    /// Response variants (at least one required).
    public let variants: [ProjectVariant]
    /// When true and at least one variant has `callCount` set, a request whose call number has
    /// no exact-matching variant is rejected (409) instead of falling back to normal selection.
    /// `nil`/`false` preserves today's relaxed behavior.
    public let strictCallCount: Bool?

    public init(
        id: String,
        alias: String? = nil,
        description: String? = nil,
        referenceName: String? = nil,
        method: String,
        path: String,
        tags: [String]? = nil,
        auth: ProjectAuthConfig? = nil,
        requestRules: RequestRules? = nil,
        operation: EndpointOperation? = nil,
        network: NetworkBehavior? = nil,
        variants: [ProjectVariant],
        strictCallCount: Bool? = nil
    ) {
        let normalizedReferenceName = referenceName?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.id = id
        self.alias =
            EndpointAlias.normalized(alias: alias)
            ?? EndpointAlias.defaultAlias(method: method, path: path, operation: operation)
        self.description = description
        self.referenceName =
            normalizedReferenceName.flatMap { $0.isEmpty ? nil : $0 }
            ?? defaultReferenceNameForEndpointId(id)
        self.method = method
        self.path = path
        self.tags = tags
        self.auth = auth
        self.requestRules = requestRules
        self.operation = operation
        self.network = network
        self.variants = variants
        self.strictCallCount = strictCallCount
    }

    enum CodingKeys: String, CodingKey {
        case id, alias, description, method, path, tags, auth
        case referenceName = "reference_name"
        case requestRules = "request_rules"
        case operation, network, variants
        case strictCallCount = "strict_call_count"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let method = try container.decode(String.self, forKey: .method)
        let path = try container.decode(String.self, forKey: .path)
        let operation = try container.decodeIfPresent(EndpointOperation.self, forKey: .operation)

        self.init(
            id: id,
            alias: try container.decodeIfPresent(String.self, forKey: .alias),
            description: try container.decodeIfPresent(String.self, forKey: .description),
            referenceName: try container.decodeIfPresent(String.self, forKey: .referenceName),
            method: method,
            path: path,
            tags: try container.decodeIfPresent([String].self, forKey: .tags),
            auth: try container.decodeIfPresent(ProjectAuthConfig.self, forKey: .auth),
            requestRules: try container.decodeIfPresent(RequestRules.self, forKey: .requestRules),
            operation: operation,
            network: try container.decodeIfPresent(NetworkBehavior.self, forKey: .network),
            variants: try container.decode([ProjectVariant].self, forKey: .variants),
            strictCallCount: try container.decodeIfPresent(Bool.self, forKey: .strictCallCount)
        )
    }
}
