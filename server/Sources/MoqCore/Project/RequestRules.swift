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
    /// Reject requests that have no body (400 `missing_request_body`).
    public let requiresBody: Bool?
    /// Media types a request body may use (`image/*` wildcards allowed); a body of any other
    /// `Content-Type` is rejected (415 `unsupported_content_type`). Empty or nil accepts any type.
    public let acceptedContentTypes: [String]?

    public init(
        headers: [RuleMatcher]? = nil,
        verifyCookies: Bool? = nil,
        queryParams: [RuleMatcher]? = nil,
        cookies: [RuleMatcher]? = nil,
        requiresBody: Bool? = nil,
        acceptedContentTypes: [String]? = nil
    ) {
        self.headers = headers
        self.verifyCookies = verifyCookies
        self.queryParams = queryParams
        self.cookies = cookies
        self.requiresBody = requiresBody
        self.acceptedContentTypes = acceptedContentTypes
    }

    enum CodingKeys: String, CodingKey {
        case headers
        case verifyCookies = "verify_cookies"
        case queryParams = "query_params"
        case cookies
        case requiresBody = "requires_body"
        case acceptedContentTypes = "accepted_content_types"
    }
}
