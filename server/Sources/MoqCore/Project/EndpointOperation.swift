/// GraphQL operation matching configuration.
public struct EndpointOperation: Codable, Sendable, Equatable {
    /// The GraphQL operation type.
    public let type: OperationType
    /// Named operation (e.g. "GetUserProfile").
    public let name: String?
    /// Normalized query document for anonymous operations.
    public let document: String?

    public init(type: OperationType, name: String? = nil, document: String? = nil) {
        self.type = type
        self.name = name
        self.document = document
    }

    public enum OperationType: String, Codable, Sendable, Equatable {
        case query
        case mutation
        case subscription
    }
}
