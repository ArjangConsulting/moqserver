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
}

public struct VariantDetail: Content {
    public let name: String
    public let statusCode: Int
    public let hasBody: Bool
    public let delay: Double?
}

public struct SetVariantRequest: Content {
    public let variant: String
}

public struct MessageResponse: Content {
    public let message: String
}
