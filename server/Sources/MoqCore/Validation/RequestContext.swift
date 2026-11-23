import Foundation

/// Framework-agnostic representation of request data for validation.
public struct RequestContext: Sendable {
    public let queryParameters: [String: String]
    public let headers: [String: String]
    public let cookies: [String: String]
    public let hasBody: Bool
    public let contentType: String?
    /// Lowercased header names to treat as credentials in validation messages, on top of
    /// `SensitiveHeaders`.
    public let redactHeaders: Set<String>

    public init(
        queryParameters: [String: String] = [:],
        headers: [String: String] = [:],
        cookies: [String: String] = [:],
        hasBody: Bool = false,
        contentType: String? = nil,
        redactHeaders: Set<String> = []
    ) {
        self.queryParameters = queryParameters
        self.headers = headers
        self.cookies = cookies
        self.hasBody = hasBody
        self.contentType = contentType
        self.redactHeaders = redactHeaders
    }
}
