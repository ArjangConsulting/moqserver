/// A mock API endpoint with its response variants.
public struct Endpoint: Sendable {
    public let key: EndpointKey
    public let authRequirement: AuthRequirement
    public let variants: [ResponseVariant]
    public let requiredQueryParameters: [String]
    public let requiredHeaders: [String]
    public let requiresBody: Bool
    public let acceptedContentTypes: [String]
    /// GraphQL operation matching (non-nil for GraphQL endpoints).
    public let operation: EndpointOperation?

    public init(
        key: EndpointKey,
        authRequirement: AuthRequirement,
        variants: [ResponseVariant],
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = [],
        operation: EndpointOperation? = nil
    ) {
        self.key = key
        self.authRequirement = authRequirement
        self.variants = variants
        self.requiredQueryParameters = requiredQueryParameters
        self.requiredHeaders = requiredHeaders
        self.requiresBody = requiresBody
        self.acceptedContentTypes = acceptedContentTypes
        self.operation = operation
    }

    /// Returns the default variant (first in the list).
    public var defaultVariant: ResponseVariant? {
        variants.first
    }

    /// Returns a variant by name, or the default if not found.
    public func variant(named name: String?) -> ResponseVariant? {
        guard let name else { return defaultVariant }
        return variants.first { $0.name == name } ?? defaultVariant
    }
}
