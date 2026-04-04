/// A mock API endpoint with its response variants.
public struct Endpoint: Sendable {
    public let key: EndpointKey
    public let authRequirement: AuthRequirement
    public let variants: [ResponseVariant]
    public let queryParamRules: [RuleMatcher]
    public let headerRules: [RuleMatcher]
    public let cookieRules: [RuleMatcher]
    public let verifyCookies: Bool
    public let requiresBody: Bool
    public let acceptedContentTypes: [String]
    /// GraphQL operation matching (non-nil for GraphQL endpoints).
    public let operation: EndpointOperation?
    /// Endpoint-level network simulation settings.
    public let network: NetworkBehavior?

    public init(
        key: EndpointKey,
        authRequirement: AuthRequirement,
        variants: [ResponseVariant],
        queryParamRules: [RuleMatcher] = [],
        headerRules: [RuleMatcher] = [],
        cookieRules: [RuleMatcher] = [],
        verifyCookies: Bool = false,
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = [],
        operation: EndpointOperation? = nil,
        network: NetworkBehavior? = nil
    ) {
        self.key = key
        self.authRequirement = authRequirement
        self.variants = variants
        self.queryParamRules = Endpoint.mergedRules(explicit: queryParamRules, requiredNames: requiredQueryParameters)
        self.headerRules = Endpoint.mergedRules(explicit: headerRules, requiredNames: requiredHeaders)
        self.cookieRules = cookieRules
        self.verifyCookies = verifyCookies
        self.requiresBody = requiresBody
        self.acceptedContentTypes = acceptedContentTypes
        self.operation = operation
        self.network = network
    }

    public var requiredQueryParameters: [String] {
        queryParamRules.filter { Self.isRequiredRule($0) }.map(\.name)
    }

    public var requiredHeaders: [String] {
        headerRules.filter { Self.isRequiredRule($0) }.map(\.name)
    }

    /// Returns the explicit default variant, or the first variant if none is marked.
    public var defaultVariant: ResponseVariant? {
        variants.first(where: \.isDefault) ?? variants.first
    }

    /// Returns a variant by name, or the default if not found.
    public func variant(named name: String?) -> ResponseVariant? {
        guard let name else { return defaultVariant }
        return variants.first { $0.matchesIdentifier(name) } ?? defaultVariant
    }

    private static func mergedRules(explicit: [RuleMatcher], requiredNames: [String]) -> [RuleMatcher] {
        guard !requiredNames.isEmpty else { return explicit }

        var rules = explicit
        for name in requiredNames where !rules.contains(where: { $0.name == name }) {
            rules.append(RuleMatcher(name: name, required: true, matchType: .require))
        }
        return rules
    }

    private static func isRequiredRule(_ rule: RuleMatcher) -> Bool {
        rule.matchType == .require || (rule.matchType == nil && rule.required == true)
    }
}
