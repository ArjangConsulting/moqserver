import Foundation

/// A parsed response from an API spec.
public struct ParsedResponse: Sendable {
    public let name: String
    public let statusCode: Int
    public let headers: [(String, String)]
    public let body: Data?
    public let requestMatch: RequestMatch?

    public init(
        name: String,
        statusCode: Int,
        headers: [(String, String)],
        body: Data?,
        requestMatch: RequestMatch? = nil
    ) {
        self.name = name
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.requestMatch = requestMatch
    }
}

/// A parsed endpoint from an API spec.
public struct ParsedEndpoint: Sendable {
    public let method: String
    public let path: String
    public let responses: [ParsedResponse]
    public let authRequirement: AuthRequirement
    public let requiredQueryParameters: [String]
    public let requiredHeaders: [String]
    public let requiresBody: Bool
    public let acceptedContentTypes: [String]

    public init(
        method: String,
        path: String,
        responses: [ParsedResponse],
        authRequirement: AuthRequirement,
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = []
    ) {
        self.method = method
        self.path = path
        self.responses = responses
        self.authRequirement = authRequirement
        self.requiredQueryParameters = requiredQueryParameters
        self.requiredHeaders = requiredHeaders
        self.requiresBody = requiresBody
        self.acceptedContentTypes = acceptedContentTypes
    }
}

/// A fully parsed API spec.
public struct ParsedSpec: Sendable {
    public let title: String
    public let version: String
    public let endpoints: [ParsedEndpoint]

    public init(title: String, version: String, endpoints: [ParsedEndpoint]) {
        self.title = title
        self.version = version
        self.endpoints = endpoints
    }
}

/// Protocol for parsing API specs into a common model.
/// Extensible for OpenAPI, AsyncAPI, Postman collections, etc.
public protocol SpecParsing: Sendable {
    func parse(data: Data) throws -> ParsedSpec
}
