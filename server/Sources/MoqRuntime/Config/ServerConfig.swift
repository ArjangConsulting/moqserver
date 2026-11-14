import Foundation
import MoqAddons
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
        public var oauth2RedirectUris: [String]?

        public init(
            bearerTokens: [String]? = nil,
            basicCredentials: [ServerBasicCredential]? = nil,
            apiKeys: [String: String]? = nil,
            oauth2Tokens: [String]? = nil,
            oauth2Clients: [OAuth2Client]? = nil,
            oauth2TokenScopes: [String: [String]]? = nil,
            oauth2RedirectUris: [String]? = nil
        ) {
            self.bearerTokens = bearerTokens
            self.basicCredentials = basicCredentials
            self.apiKeys = apiKeys
            self.oauth2Tokens = oauth2Tokens
            self.oauth2Clients = oauth2Clients
            self.oauth2TokenScopes = oauth2TokenScopes
            self.oauth2RedirectUris = oauth2RedirectUris
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
        if let globalDelay, !globalDelay.isFinite || globalDelay < 0 {
            errors.append("globalDelay must be finite and non-negative.")
        }
        for (endpoint, delay) in delayOverrides ?? [:] where !delay.isFinite || delay < 0 {
            errors.append("delayOverrides[\(endpoint)] must be finite and non-negative.")
        }
        for redirectUri in auth?.oauth2RedirectUris ?? [] where Self.validRedirectURI(redirectUri) == nil {
            errors.append("OAuth redirect URI is invalid: \(redirectUri)")
        }
        return errors
    }

    /// `variantOverrides`/`delayOverrides` entries that name no served endpoint (typically a typo in
    /// the `METHOD /path` key), or a variant the endpoint doesn't have. These would otherwise be
    /// silently ignored.
    public func unknownOverrideWarnings(endpoints: [Endpoint]) -> [String] {
        let byKey = Dictionary(endpoints.map { ($0.stateKey, $0) }, uniquingKeysWith: { first, _ in first })
        var warnings: [String] = []
        for (key, variant) in (variantOverrides ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard let endpoint = byKey[key] else {
                let hint = Self.suggestion(for: key, in: byKey.keys)
                warnings.append("variantOverrides[\(key)] names no endpoint\(hint).")
                continue
            }
            if !endpoint.variants.contains(where: { $0.matchesIdentifier(variant) }) {
                let names = endpoint.variants.map(\.name).joined(separator: ", ")
                warnings.append(
                    "variantOverrides[\(key)] = \(variant) names no variant of that endpoint (have: \(names)).")
            }
        }
        for key in (delayOverrides ?? [:]).keys.sorted() where byKey[key] == nil {
            warnings.append("delayOverrides[\(key)] names no endpoint\(Self.suggestion(for: key, in: byKey.keys)).")
        }
        return warnings
    }

    /// `; did you mean …?` for the known key that differs only in case or a trailing slash.
    private static func suggestion(for key: String, in known: Dictionary<String, Endpoint>.Keys) -> String {
        func normalized(_ value: String) -> String {
            var lowered = value.lowercased()
            while lowered.hasSuffix("/") { lowered.removeLast() }
            return lowered
        }
        let target = normalized(key)
        guard let match = known.first(where: { normalized($0) == target }) else {
            return "; keys are `METHOD /path` as listed by GET /_admin/endpoints"
        }
        return "; did you mean `\(match)`?"
    }

    static func validRedirectURI(_ value: String) -> URLComponents? {
        OAuthMockConfig.validRedirectURI(value)
    }

    /// The `oauth-mock` add-on's settings, taken from the `auth` section.
    public var oauthMockConfig: OAuthMockConfig {
        OAuthMockConfig(
            oauth2Clients: auth?.oauth2Clients?.map { .init(clientId: $0.clientId, clientSecret: $0.clientSecret) },
            basicCredentials: auth?.basicCredentials?.map { .init(username: $0.username, password: $0.password) },
            oauth2Tokens: auth?.oauth2Tokens,
            oauth2TokenScopes: auth?.oauth2TokenScopes,
            oauth2RedirectUris: auth?.oauth2RedirectUris)
    }

    public func variantOverride(for endpointKey: String) -> String? {
        variantOverrides?[endpointKey]
    }

    public func effectiveDelay(for endpointKey: String) -> TimeInterval? {
        delayOverrides?[endpointKey] ?? globalDelay
    }
}
