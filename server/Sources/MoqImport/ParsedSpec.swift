import MoqCore

/// A fully parsed API spec — the intermediate model between a raw OpenAPI/HAR document and a
/// `MoqProject`. Mirrors Kotlin's `com.moqserver.studio.domain.ParsedSpec`.
public struct ParsedSpec: Sendable, Equatable {
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
public struct ParsedEndpoint: Sendable, Equatable {
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
        cookies: [RuleMatcher] = []
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
    }
}

/// A single response variant parsed from an API spec.
public struct ParsedResponse: Sendable, Equatable {
    public let name: String
    public let statusCode: Int
    public let headers: [String: String]
    /// Response body as a raw string (JSON text, plain text, etc.) — parsed into an
    /// `AnyCodableValue` by `ImportConverter` at conversion time, not here.
    public let body: String?
    public let description: String?

    public init(
        name: String,
        statusCode: Int,
        headers: [String: String] = [:],
        body: String? = nil,
        description: String? = nil
    ) {
        self.name = name
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.description = description
    }
}
