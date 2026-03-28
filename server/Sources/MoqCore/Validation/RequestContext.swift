import Foundation

/// Framework-agnostic representation of request data for validation.
public struct RequestContext: Sendable {
    public let queryParameters: [String: String]
    public let headers: [String: String]
    public let hasBody: Bool
    public let contentType: String?

    public init(
        queryParameters: [String: String] = [:],
        headers: [String: String] = [:],
        hasBody: Bool = false,
        contentType: String? = nil
    ) {
        self.queryParameters = queryParameters
        self.headers = headers
        self.hasBody = hasBody
        self.contentType = contentType
    }
}
