import Vapor

/// DTOs for admin API responses.
public struct EndpointListItem: Content {
    public let method: String
    public let path: String
    /// The key call counts, overrides, scenarios and config overrides use (`Endpoint.stateKey`).
    public let key: String
    /// GraphQL operation selector; pass it as `?operation=` to address this operation via the
    /// per-endpoint admin routes. `nil` for REST endpoints.
    public let operation: String?
    public let variants: [String]
    public let activeVariant: String?
    public let currentCallCount: Int
}

public struct EndpointDetail: Content {
    public let method: String
    public let path: String
    public let key: String
    public let operation: String?
    public let authRequirement: String
    public let variants: [VariantDetail]
    public let activeVariant: String?
    /// Running per-endpoint call counter (0 if never called).
    public let currentCallCount: Int
    public let strictCallCount: Bool
}

public struct VariantDetail: Content {
    public let name: String
    public let statusCode: Int
    public let hasBody: Bool
    public let delay: Double?
    /// Configured `call_count`, if this variant is scoped to a specific call number.
    public let callCount: Int?
}

public struct SetVariantRequest: Content {
    public let variant: String
    /// Apply the override to only the next `times` requests that reach variant selection, then
    /// clear it. `nil` keeps it until reset.
    public let times: Int?

    public init(variant: String, times: Int? = nil) {
        self.variant = variant
        self.times = times
    }
}

public struct MessageResponse: Content {
    public let message: String
}
