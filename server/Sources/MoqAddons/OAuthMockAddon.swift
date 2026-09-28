import Foundation
import Logging
import MoqAddonKit
import MoqCore

private let logger = Logger(label: "moqserver.addons.OAuthMockAddon")

/// What `oauth-mock` accepts and issues. Keys match the `auth` section of the server config file,
/// which is where `serve` reads them from.
public struct OAuthMockConfig: Codable, Sendable, Equatable {
    public struct Client: Codable, Sendable, Equatable {
        public let clientId: String
        public let clientSecret: String

        public init(clientId: String, clientSecret: String) {
            self.clientId = clientId
            self.clientSecret = clientSecret
        }
    }

    public struct Credential: Codable, Sendable, Equatable {
        public let username: String
        public let password: String

        public init(username: String, password: String) {
            self.username = username
            self.password = password
        }
    }

    /// Clients accepted by `client_credentials`. Empty accepts any non-empty id and secret.
    public var oauth2Clients: [Client]?
    /// Users accepted by `password`. Empty accepts any non-empty username and password.
    public var basicCredentials: [Credential]?
    /// Tokens to issue. Empty issues a random mock token.
    public var oauth2Tokens: [String]?
    /// Scopes each configured token grants, used to pick a token for a requested scope.
    public var oauth2TokenScopes: [String: [String]]?
    /// Allowed `redirect_uri` values. Defaults to `http://localhost/callback`.
    public var oauth2RedirectUris: [String]?

    public init(
        oauth2Clients: [Client]? = nil,
        basicCredentials: [Credential]? = nil,
        oauth2Tokens: [String]? = nil,
        oauth2TokenScopes: [String: [String]]? = nil,
        oauth2RedirectUris: [String]? = nil
    ) {
        self.oauth2Clients = oauth2Clients
        self.basicCredentials = basicCredentials
        self.oauth2Tokens = oauth2Tokens
        self.oauth2TokenScopes = oauth2TokenScopes
        self.oauth2RedirectUris = oauth2RedirectUris
    }

    /// An absolute http(s) URL with a host and no credentials or fragment.
    public static func validRedirectURI(_ value: String) -> URLComponents? {
        guard let components = URLComponents(string: value),
            let scheme = components.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            components.host != nil,
            components.user == nil,
            components.password == nil,
            components.fragment == nil
        else {
            return nil
        }
        return components
    }
}

/// `oauth-mock`: a mock OAuth 2 token endpoint (`POST token`) and authorization endpoint
/// (`GET authorize`), mounted at `/_addons/oauth-mock/` and, for compatibility, at `/_auth/`.
///
/// Unlike bundle add-ons it is always active: `buildApp` creates it from the server config's
/// `auth` section, so existing `/_auth` users keep working without editing their bundles.
public struct OAuthMockAddon: MoqAddon {
    public static let id = "oauth-mock"

    let config: OAuthMockConfig

    public init(config: OAuthMockConfig = OAuthMockConfig()) {
        self.config = config
    }

    public init(config: AnyCodableValue, environment: AddonEnvironment) throws {
        do {
            self.init(config: try Self.decode(config))
        } catch {
            throw AddonActivationError(addonID: Self.id, message: "Invalid config: \(error)")
        }
    }

    public static func validateConfig(_ config: AnyCodableValue) -> [AddonDiagnostic] {
        do {
            let decoded = try decode(config)
            return (decoded.oauth2RedirectUris ?? []).filter { OAuthMockConfig.validRedirectURI($0) == nil }.map {
                AddonDiagnostic(field: "oauth2RedirectUris", message: "OAuth redirect URI is invalid: \($0)")
            }
        } catch {
            return [AddonDiagnostic(message: "Invalid config: \(error)")]
        }
    }

    private static func decode(_ config: AnyCodableValue) throws -> OAuthMockConfig {
        if case .null = config { return OAuthMockConfig() }
        return try JSONDecoder().decode(OAuthMockConfig.self, from: JSONEncoder().encode(config))
    }

    // MARK: - H1

    public var routes: [AddonRoute] {
        [
            AddonRoute(method: "POST", path: ["token"]) { request in handleToken(request) },
            AddonRoute(method: "GET", path: ["authorize"]) { request in handleAuthorize(request) },
        ]
    }

    public var compatibilityRoutePrefixes: [[String]] { [["_auth"]] }

    // MARK: - Token endpoint

    func handleToken(_ request: AddonHTTPRequest) -> AddonHTTPResponse {
        logger.info("Token request received")
        let parameters = request.bodyParameters()
        guard let grantType = parameters["grant_type"] else {
            logger.warning("Token request missing grant_type")
            return oauthError("unsupported_grant_type", "Missing grant_type parameter")
        }

        switch grantType {
        case "client_credentials":
            logger.debug("Handling client_credentials grant")
            return handleClientCredentials(request, parameters)
        case "password":
            logger.debug("Handling password grant")
            return handlePasswordGrant(parameters)
        case "authorization_code":
            logger.debug("Handling authorization_code grant")
            return handleAuthorizationCodeGrant(parameters)
        case "refresh_token":
            logger.debug("Handling refresh_token grant")
            return handleRefreshTokenGrant(parameters)
        default:
            logger.warning("Unsupported grant type: \(grantType)")
            return oauthError("unsupported_grant_type", "Grant type '\(grantType)' is not supported")
        }
    }

    private func handleClientCredentials(
        _ request: AddonHTTPRequest, _ parameters: [String: String]
    ) -> AddonHTTPResponse {
        let (headerClientId, headerClientSecret) = basicAuth(request)
        let clientId = parameters["client_id"] ?? headerClientId
        let clientSecret = parameters["client_secret"] ?? headerClientSecret

        guard let clientId, !clientId.isEmpty, let clientSecret, !clientSecret.isEmpty else {
            return oauthError("invalid_client", "Missing client credentials")
        }
        if let clients = config.oauth2Clients, !clients.isEmpty {
            guard
                clients.contains(where: {
                    SecureCompare.equals(clientId, $0.clientId) && SecureCompare.equals(clientSecret, $0.clientSecret)
                })
            else {
                return oauthError("invalid_client", "Invalid client credentials")
            }
        }
        return tokenResponse(scope: parameters["scope"])
    }

    private func handlePasswordGrant(_ parameters: [String: String]) -> AddonHTTPResponse {
        guard let username = parameters["username"], !username.isEmpty,
            let password = parameters["password"], !password.isEmpty
        else {
            return oauthError("invalid_grant", "Missing username or password")
        }
        if let credentials = config.basicCredentials, !credentials.isEmpty {
            guard
                credentials.contains(where: {
                    SecureCompare.equals(username, $0.username) && SecureCompare.equals(password, $0.password)
                })
            else {
                return oauthError("invalid_grant", "Invalid username or password")
            }
        }
        return tokenResponse(scope: parameters["scope"])
    }

    private func handleAuthorizationCodeGrant(_ parameters: [String: String]) -> AddonHTTPResponse {
        guard let code = parameters["code"], !code.isEmpty else {
            return oauthError("invalid_grant", "Missing authorization code")
        }
        guard let redirectUri = parameters["redirect_uri"], isAllowedRedirectURI(redirectUri) else {
            return oauthError("invalid_grant", "Invalid redirect_uri")
        }
        return tokenResponse(scope: parameters["scope"])
    }

    private func handleRefreshTokenGrant(_ parameters: [String: String]) -> AddonHTTPResponse {
        guard let refreshToken = parameters["refresh_token"], !refreshToken.isEmpty else {
            return oauthError("invalid_grant", "Missing refresh_token")
        }
        return tokenResponse(scope: nil)
    }

    // MARK: - Authorization endpoint

    func handleAuthorize(_ request: AddonHTTPRequest) -> AddonHTTPResponse {
        guard request.query["response_type"] == "code" else {
            return oauthError("unsupported_response_type", "response_type must be code")
        }
        guard let redirectUri = request.query["redirect_uri"], isAllowedRedirectURI(redirectUri) else {
            return oauthError("invalid_request", "redirect_uri is not allowed")
        }

        // Build the redirect with URLComponents so the code and state values are
        // properly percent-encoded instead of interpolated raw into the header.
        guard var components = OAuthMockConfig.validRedirectURI(redirectUri) else {
            return oauthError("invalid_request", "redirect_uri is not a valid URL")
        }
        var queryItems = components.queryItems ?? []
        queryItems.append(URLQueryItem(name: "code", value: "mock-auth-code-\(UUID().uuidString.prefix(8))"))
        if let state = request.query["state"] {
            queryItems.append(URLQueryItem(name: "state", value: state))
        }
        components.queryItems = queryItems
        guard let location = components.string else {
            return oauthError("invalid_request", "redirect_uri is not a valid URL")
        }

        return AddonHTTPResponse(
            status: 302,
            headers: ["Location": location, "Content-Type": "application/json"],
            body: AnyCodableValue.object(["message": .string("Redirecting to \(redirectUri)")]).toJSONData())
    }

    // MARK: - Helpers

    private func tokenResponse(scope: String?) -> AddonHTTPResponse {
        let requestedScopes = Set((scope ?? "").split(separator: " ").map(String.init))
        let configuredTokens = config.oauth2Tokens ?? []
        let tokenScopes = config.oauth2TokenScopes ?? [:]

        let accessToken: String
        if !requestedScopes.isEmpty,
            let matched = configuredTokens.first(where: { token in
                requestedScopes.isSubset(of: Set(tokenScopes[token] ?? []))
            })
        {
            accessToken = matched
        } else {
            accessToken = configuredTokens.first ?? "mock-access-token-\(UUID().uuidString.prefix(8))"
        }

        var body: [String: AnyCodableValue] = [
            "access_token": .string(accessToken),
            "token_type": .string("Bearer"),
            "expires_in": .int(3600),
            "refresh_token": .string("mock-refresh-token-\(UUID().uuidString.prefix(8))"),
        ]
        if let scope {
            body["scope"] = .string(scope)
        }
        return AddonHTTPResponse(
            status: 200,
            headers: ["Content-Type": "application/json", "Cache-Control": "no-store", "Pragma": "no-cache"],
            body: AnyCodableValue.object(body).toJSONData())
    }

    private func oauthError(_ error: String, _ description: String) -> AddonHTTPResponse {
        .json(.object(["error": .string(error), "error_description": .string(description)]), status: 400)
    }

    private func basicAuth(_ request: AddonHTTPRequest) -> (String?, String?) {
        guard let header = request.header("Authorization"), header.lowercased().hasPrefix("basic "),
            let decoded = Data(base64Encoded: String(header.dropFirst("Basic ".count))),
            let credentials = String(data: decoded, encoding: .utf8)
        else {
            return (nil, nil)
        }
        let parts = credentials.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return (nil, nil) }
        return (String(parts[0]), String(parts[1]))
    }

    private func isAllowedRedirectURI(_ redirectUri: String) -> Bool {
        guard OAuthMockConfig.validRedirectURI(redirectUri) != nil else { return false }
        let allowed = config.oauth2RedirectUris ?? ["http://localhost/callback"]
        return allowed.contains(redirectUri)
    }
}
