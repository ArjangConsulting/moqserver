import Foundation

/// A single mock response variant for an endpoint.
public struct ResponseVariant: Sendable {
    /// Variant name used for selection via `X-Mock-Variant` header.
    public let name: String

    /// HTTP status code.
    public let statusCode: HTTPStatusCode

    /// Response headers.
    public let headers: [(String, String)]

    /// Raw response body data.
    public let body: Data?

    /// Optional delay in seconds before responding.
    public let delay: TimeInterval?

    /// Optional request matching used to pick this variant.
    public let requestMatch: RequestMatch?

    public init(
        name: String,
        statusCode: HTTPStatusCode = .ok,
        headers: [(String, String)] = [("Content-Type", "application/json")],
        body: Data? = nil,
        delay: TimeInterval? = nil,
        requestMatch: RequestMatch? = nil
    ) {
        self.name = name
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.delay = delay
        self.requestMatch = requestMatch
    }
}
