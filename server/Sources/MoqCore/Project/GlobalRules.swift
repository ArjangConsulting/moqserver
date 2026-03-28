/// Project-level global request rules.
public struct GlobalRules: Codable, Sendable, Equatable {
    /// Required headers applied to all endpoints.
    public let requiredHeaders: [RuleMatcher]?
    /// Whether to verify cookie presence globally.
    public let verifyCookies: Bool?

    public init(requiredHeaders: [RuleMatcher]? = nil, verifyCookies: Bool? = nil) {
        self.requiredHeaders = requiredHeaders
        self.verifyCookies = verifyCookies
    }

    enum CodingKeys: String, CodingKey {
        case requiredHeaders = "required_headers"
        case verifyCookies = "verify_cookies"
    }
}
