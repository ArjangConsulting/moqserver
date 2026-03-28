import Foundation

/// Framework-agnostic representation of auth-relevant request data.
/// Extracted from the HTTP request so auth validation can run without Vapor.
public struct AuthContext: Sendable {
    public let authorizationHeader: String?

    public init(authorizationHeader: String?) {
        self.authorizationHeader = authorizationHeader
    }
}

/// Configuration values needed for auth validation.
public struct AuthConfig: Sendable {
    public let bearerTokens: [String]?
    public let basicCredentials: [BasicCredential]?
    public let apiKeys: [String: String]?
    public let oauth2Tokens: [String]?
    public let oauth2TokenScopes: [String: [String]]?

    public init(
        bearerTokens: [String]? = nil,
        basicCredentials: [BasicCredential]? = nil,
        apiKeys: [String: String]? = nil,
        oauth2Tokens: [String]? = nil,
        oauth2TokenScopes: [String: [String]]? = nil
    ) {
        self.bearerTokens = bearerTokens
        self.basicCredentials = basicCredentials
        self.apiKeys = apiKeys
        self.oauth2Tokens = oauth2Tokens
        self.oauth2TokenScopes = oauth2TokenScopes
    }
}

/// A basic auth credential pair.
public struct BasicCredential: Sendable, Equatable {
    public let username: String
    public let password: String

    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}
