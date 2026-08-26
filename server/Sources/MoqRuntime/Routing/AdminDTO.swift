import Vapor

/// DTOs for admin API responses.
public struct EndpointListItem: Content {
    public let method: String
    public let path: String
    public let variants: [String]
    public let activeVariant: String?
}

public struct EndpointDetail: Content {
    public let method: String
    public let path: String
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
}

public struct MessageResponse: Content {
    public let message: String
}
