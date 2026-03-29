/// Request validation rules for an endpoint in the .moqproj format.
public struct RequestRules: Codable, Sendable, Equatable {
    /// Header validation rules.
    public let headers: [RuleMatcher]?
    /// Whether to verify cookie presence.
    public let verifyCookies: Bool?
    /// Query parameter validation rules.
    public let queryParams: [RuleMatcher]?
    /// Cookie validation rules.
    public let cookies: [RuleMatcher]?

    public init(
        headers: [RuleMatcher]? = nil,
        verifyCookies: Bool? = nil,
        queryParams: [RuleMatcher]? = nil,
        cookies: [RuleMatcher]? = nil
    ) {
        self.headers = headers
        self.verifyCookies = verifyCookies
        self.queryParams = queryParams
        self.cookies = cookies
    }

    enum CodingKeys: String, CodingKey {
        case headers
        case verifyCookies = "verify_cookies"
        case queryParams = "query_params"
        case cookies
    }
}
