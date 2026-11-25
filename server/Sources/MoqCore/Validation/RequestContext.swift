import Foundation

/// Framework-agnostic representation of request data for validation.
public struct RequestContext: Sendable {
    public let queryParameters: [String: String]
    public let headers: [String: String]
    public let cookies: [String: String]
    public let hasBody: Bool
    public let contentType: String?
    /// Which names are credentials, so validation messages never echo their values.
    public let credentialNames: CredentialNames

    public init(
        queryParameters: [String: String] = [:],
        headers: [String: String] = [:],
        cookies: [String: String] = [:],
        hasBody: Bool = false,
        contentType: String? = nil,
        credentialNames: CredentialNames = .builtIn
    ) {
        self.queryParameters = queryParameters
        self.headers = headers
        self.cookies = cookies
        self.hasBody = hasBody
        self.contentType = contentType
        self.credentialNames = credentialNames
    }
}
