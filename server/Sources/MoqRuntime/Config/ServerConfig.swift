import Foundation
import MoqCore

/// Server configuration loaded from a YAML or JSON config file.
public struct ServerConfig: Codable, Sendable, ServerConfiguring {
    public var variantOverrides: [String: String]?
    public var globalDelay: TimeInterval?
    public var delayOverrides: [String: TimeInterval]?
    public var auth: ServerAuthConfig?
    public var overridesPersistencePath: String?
    public var admin: AdminConfig?

    public init(
        variantOverrides: [String: String]? = nil,
        globalDelay: TimeInterval? = nil,
        delayOverrides: [String: TimeInterval]? = nil,
        auth: ServerAuthConfig? = nil,
        overridesPersistencePath: String? = nil,
        admin: AdminConfig? = nil
    ) {
        self.variantOverrides = variantOverrides
        self.globalDelay = globalDelay
        self.delayOverrides = delayOverrides
        self.auth = auth
        self.overridesPersistencePath = overridesPersistencePath
        self.admin = admin
    }

    public struct ServerAuthConfig: Codable, Sendable {
        public var bearerTokens: [String]?
        public var basicCredentials: [ServerBasicCredential]?
        public var apiKeys: [String: String]?
        public var oauth2Tokens: [String]?
        public var oauth2Clients: [OAuth2Client]?
        public var oauth2TokenScopes: [String: [String]]?

        public init(
            bearerTokens: [String]? = nil,
            basicCredentials: [ServerBasicCredential]? = nil,
            apiKeys: [String: String]? = nil,
            oauth2Tokens: [String]? = nil,
            oauth2Clients: [OAuth2Client]? = nil,
            oauth2TokenScopes: [String: [String]]? = nil
        ) {
            self.bearerTokens = bearerTokens
            self.basicCredentials = basicCredentials
            self.apiKeys = apiKeys
            self.oauth2Tokens = oauth2Tokens
            self.oauth2Clients = oauth2Clients
            self.oauth2TokenScopes = oauth2TokenScopes
        }

        /// Convert to MoqCore's AuthConfig for use with AuthValidator.
        public func toAuthConfig() -> AuthConfig {
            AuthConfig(
                bearerTokens: bearerTokens,
                basicCredentials: basicCredentials?.map {
                    BasicCredential(username: $0.username, password: $0.password)
                },
                apiKeys: apiKeys,
                oauth2Tokens: oauth2Tokens,
                oauth2TokenScopes: oauth2TokenScopes
            )
        }
    }

    public struct ServerBasicCredential: Codable, Sendable {
        public let username: String
        public let password: String

        public init(username: String, password: String) {
            self.username = username
            self.password = password
        }
    }

    public struct OAuth2Client: Codable, Sendable {
        public let clientId: String
        public let clientSecret: String

        public init(clientId: String, clientSecret: String) {
            self.clientId = clientId
            self.clientSecret = clientSecret
        }
    }

    public struct AdminConfig: Codable, Sendable {
        public var bearerToken: String?
        public var apiKeyHeader: String?
        public var apiKey: String?

        public init(bearerToken: String? = nil, apiKeyHeader: String? = nil, apiKey: String? = nil) {
            self.bearerToken = bearerToken
            self.apiKeyHeader = apiKeyHeader
            self.apiKey = apiKey
        }
    }

    /// Configuration problems that should prevent server startup.
    public func validationErrors() -> [String] {
        var errors: [String] = []
        if let admin, admin.bearerToken == nil, admin.apiKey == nil {
            errors.append(
                "The `admin` section must set `bearerToken` or `apiKey`. An empty admin block would reject every admin request with no way to authenticate."
            )
        }
        return errors
    }

    public func variantOverride(for endpointKey: String) -> String? {
        variantOverrides?[endpointKey]
    }

    public func effectiveDelay(for endpointKey: String) -> TimeInterval? {
        delayOverrides?[endpointKey] ?? globalDelay
    }
}
