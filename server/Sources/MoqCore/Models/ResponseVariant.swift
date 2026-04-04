import Foundation

/// A single mock response variant for an endpoint.
public struct ResponseVariant: Sendable {
    /// Variant name used for selection via `X-Mock-Variant` header.
    public let name: String

    /// Stable code-friendly identifier used by Studio and automation.
    public let referenceName: String

    /// Whether this variant is marked as the default.
    public let isDefault: Bool

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
        referenceName: String? = nil,
        isDefault: Bool = false,
        statusCode: HTTPStatusCode = .ok,
        headers: [(String, String)] = [("Content-Type", "application/json")],
        body: Data? = nil,
        delay: TimeInterval? = nil,
        requestMatch: RequestMatch? = nil
    ) {
        self.name = name
        self.referenceName = referenceName ?? defaultReferenceNameForVariantName(name)
        self.isDefault = isDefault
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.delay = delay
        self.requestMatch = requestMatch
    }

    public func matchesIdentifier(_ identifier: String) -> Bool {
        name == identifier || referenceName == identifier
    }
}
