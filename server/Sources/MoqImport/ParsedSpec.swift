import MoqCore

/// A fully parsed API spec — the intermediate model between a raw OpenAPI/HAR document and a
/// `MoqProject`. Mirrors Kotlin's `com.moqserver.studio.domain.ParsedSpec`, and `Codable` so it
/// can travel as-is over `moq-format`'s `import.parseHar`/`import.parseOpenapi` — the parse step
/// on its own, letting a caller (Studio) hold the result for interactive review before any of it
/// is merged into a project or written to disk.
public struct ParsedSpec: Codable, Sendable, Equatable {
    public let title: String
    public let version: String
    public let endpoints: [ParsedEndpoint]
    /// Non-fatal issues surfaced during parsing (skipped entries, redaction notices, etc.) for
    /// the caller to relay to whoever's driving the import.
    public let warnings: [String]

    public init(title: String, version: String, endpoints: [ParsedEndpoint], warnings: [String] = []) {
        self.title = title
        self.version = version
        self.endpoints = endpoints
        self.warnings = warnings
    }
}

/// A single endpoint parsed from an API spec, not yet assigned a stable id or reference name.
public struct ParsedEndpoint: Codable, Sendable, Equatable {
    public let method: String
    public let path: String
    public let alias: String?
    public let description: String?
    public let referenceName: String?
    public let tags: [String]
    public let responses: [ParsedResponse]
    public let authType: ProjectAuthConfig.AuthType
    public let authHeaderName: String?
    public let queryParameters: [RuleMatcher]
    public let requiredQueryParameters: [String]
    public let requiredHeaders: [String]
    public let cookies: [RuleMatcher]
    /// OpenAPI only: whether the operation's `requestBody` is marked `required`. Not derivable
    /// from HAR, which has no schema — always `false` there. Surfaced so an AI-assisted variant
    /// generator (Studio) can tell a model what the real endpoint actually expects.
    public let requiresBody: Bool
    /// OpenAPI only: the request body's declared media types (`requestBody.content` keys, e.g.
    /// `application/json`). Empty for HAR, same reasoning as `requiresBody`.
    public let acceptedContentTypes: [String]

    enum CodingKeys: String, CodingKey {
        case method, path, alias, description, tags, responses, cookies
        case referenceName = "reference_name"
        case authType = "auth_type"
        case authHeaderName = "auth_header_name"
        case queryParameters = "query_parameters"
        case requiredQueryParameters = "required_query_parameters"
        case requiredHeaders = "required_headers"
        case requiresBody = "requires_body"
        case acceptedContentTypes = "accepted_content_types"
    }

    public init(
        method: String,
        path: String,
        alias: String? = nil,
        description: String? = nil,
        referenceName: String? = nil,
        tags: [String] = [],
        responses: [ParsedResponse],
        authType: ProjectAuthConfig.AuthType = .none,
        authHeaderName: String? = nil,
        queryParameters: [RuleMatcher] = [],
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        cookies: [RuleMatcher] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = []
    ) {
        self.method = method
        self.path = path
        self.alias = alias
        self.description = description
        self.referenceName = referenceName
        self.tags = tags
        self.responses = responses
        self.authType = authType
        self.authHeaderName = authHeaderName
        self.queryParameters = queryParameters
        self.requiredQueryParameters = requiredQueryParameters
        self.requiredHeaders = requiredHeaders
        self.cookies = cookies
        self.requiresBody = requiresBody
        self.acceptedContentTypes = acceptedContentTypes
    }
}

/// A single response variant parsed from an API spec.
public struct ParsedResponse: Codable, Sendable, Equatable {
    public let name: String
    public let statusCode: Int
    public let headers: [String: String]
    /// Response body as a raw string — parsed into an `AnyCodableValue` by `ImportConverter` at
    /// conversion time, not here, *unless* [isBase64] is set, in which case the string is base64
    /// and must never be run through a JSON-parse attempt (see `ImportConverter.convertVariant`).
    public let body: String?
    /// True when [body] holds base64 rather than the literal payload — HAR records this
    /// explicitly on response content (`response.content.encoding == "base64"`) for a binary
    /// MIME type. Carried through so the resulting variant can declare `body_encoding: base64`
    /// rather than a downstream writer guessing from Content-Type, and so the base64 text itself
    /// is never mistaken for literal response text (it used to be — see the commit that added
    /// this field for the corruption that caused).
    public let isBase64: Bool
    public let description: String?

    enum CodingKeys: String, CodingKey {
        case name, headers, body, description
        case statusCode = "status_code"
        case isBase64 = "is_base64"
    }

    public init(
        name: String,
        statusCode: Int,
        headers: [String: String] = [:],
        body: String? = nil,
        isBase64: Bool = false,
        description: String? = nil
    ) {
        self.name = name
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.isBase64 = isBase64
        self.description = description
    }
}
