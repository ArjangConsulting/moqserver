import Foundation

import Logging
import Vapor

import MoqCore

private let logger = Logger(label: "moqserver.runtime.AuthRouter")

/// Mock OAuth2/auth endpoints under `/_auth/*`.
/// Provides a token endpoint that issues mock access tokens for testing.
public struct AuthRouter {
    let config: ServerConfig?

    public init(config: ServerConfig? = nil) {
        self.config = config
    }

    public func registerRoutes(on app: Application) {
        logger.info("Registering auth routes under /_auth")
        let auth = app.grouped("_auth")

        auth.post("token") { req async throws -> Response in
            try await handleToken(req: req)
        }

        auth.get("authorize") { req async throws -> Response in
            handleAuthorize(req: req)
        }
    }

    private func handleToken(req: Request) async throws -> Response {
        logger.info("Token request received")
        let grantType: String

        if let formGrant = try? req.content.get(String.self, at: "grant_type") {
            grantType = formGrant
        } else {
            logger.warning("Token request missing grant_type")
            return oauthErrorResponse(error: "unsupported_grant_type", description: "Missing grant_type parameter")
        }

        switch grantType {
        case "client_credentials":
            logger.debug("Handling client_credentials grant")
            return handleClientCredentials(req: req)
        case "password":
            logger.debug("Handling password grant")
            return handlePasswordGrant(req: req)
        case "authorization_code":
            logger.debug("Handling authorization_code grant")
            return handleAuthorizationCodeGrant(req: req)
        case "refresh_token":
            logger.debug("Handling refresh_token grant")
            return handleRefreshTokenGrant()
        default:
            logger.warning("Unsupported grant type: \(grantType)")
            return oauthErrorResponse(error: "unsupported_grant_type", description: "Grant type '\(grantType)' is not supported")
        }
    }

    private func handleClientCredentials(req: Request) -> Response {
        let clientId = try? req.content.get(String.self, at: "client_id")
        let clientSecret = try? req.content.get(String.self, at: "client_secret")

        let (headerClientId, headerClientSecret) = extractBasicAuth(req: req)
        let finalClientId = clientId ?? headerClientId
        let finalClientSecret = clientSecret ?? headerClientSecret

        if let clients = config?.auth?.oauth2Clients, !clients.isEmpty {
            guard let id = finalClientId, let secret = finalClientSecret,
                  clients.contains(where: {
                      SecureCompare.equals(id, $0.clientId) && SecureCompare.equals(secret, $0.clientSecret)
                  }) else {
                return oauthErrorResponse(error: "invalid_client", description: "Invalid client credentials")
            }
        }

        return tokenResponse(scope: try? req.content.get(String.self, at: "scope"))
    }

    private func handlePasswordGrant(req: Request) -> Response {
        let username = try? req.content.get(String.self, at: "username")
        let password = try? req.content.get(String.self, at: "password")

        if let validCreds = config?.auth?.basicCredentials, !validCreds.isEmpty {
            guard let u = username, let p = password,
                  validCreds.contains(where: {
                      SecureCompare.equals(u, $0.username) && SecureCompare.equals(p, $0.password)
                  }) else {
                return oauthErrorResponse(error: "invalid_grant", description: "Invalid username or password")
            }
        }

        return tokenResponse(scope: try? req.content.get(String.self, at: "scope"))
    }

    private func handleAuthorizationCodeGrant(req: Request) -> Response {
        guard let _ = try? req.content.get(String.self, at: "code") else {
            return oauthErrorResponse(error: "invalid_grant", description: "Missing authorization code")
        }
        return tokenResponse(scope: try? req.content.get(String.self, at: "scope"))
    }

    private func handleRefreshTokenGrant() -> Response {
        return tokenResponse(scope: nil)
    }

    private func handleAuthorize(req: Request) -> Response {
        let redirectUri = req.query[String.self, at: "redirect_uri"] ?? "http://localhost/callback"
        let state = req.query[String.self, at: "state"]

        // Build the redirect with URLComponents so the code and state values are
        // properly percent-encoded instead of interpolated raw into the header.
        guard var components = URLComponents(string: redirectUri) else {
            return oauthErrorResponse(error: "invalid_request", description: "redirect_uri is not a valid URL")
        }
        var queryItems = components.queryItems ?? []
        queryItems.append(URLQueryItem(name: "code", value: "mock-auth-code-\(UUID().uuidString.prefix(8))"))
        if let state {
            queryItems.append(URLQueryItem(name: "state", value: state))
        }
        components.queryItems = queryItems

        guard let location = components.string else {
            return oauthErrorResponse(error: "invalid_request", description: "redirect_uri is not a valid URL")
        }

        let messageBody: [String: Any] = ["message": "Redirecting to \(redirectUri)"]
        let bodyData = (try? JSONSerialization.data(withJSONObject: messageBody)) ?? Data()
        return Response(
            status: .found,
            headers: [
                "Location": location,
                "Content-Type": "application/json",
            ],
            body: .init(data: bodyData)
        )
    }

    // MARK: - Helpers

    private func tokenResponse(scope: String?) -> Response {
        let requestedScopes = Set((scope ?? "").split(separator: " ").map(String.init))
        let configuredTokens = config?.auth?.oauth2Tokens ?? []
        let tokenScopes = config?.auth?.oauth2TokenScopes ?? [:]

        let accessToken: String
        if !requestedScopes.isEmpty,
           let matched = configuredTokens.first(where: { token in
               let granted = Set(tokenScopes[token] ?? [])
               return requestedScopes.isSubset(of: granted)
           }) {
            accessToken = matched
        } else {
            accessToken = configuredTokens.first ?? "mock-access-token-\(UUID().uuidString.prefix(8))"
        }

        var tokenBody: [String: Any] = [
            "access_token": accessToken,
            "token_type": "Bearer",
            "expires_in": 3600,
            "refresh_token": "mock-refresh-token-\(UUID().uuidString.prefix(8))",
        ]
        if let scope {
            tokenBody["scope"] = scope
        }

        guard let data = try? JSONSerialization.data(withJSONObject: tokenBody, options: [.sortedKeys]) else {
            return Response(status: .internalServerError)
        }
        return Response(
            status: .ok,
            headers: [
                "Content-Type": "application/json",
                "Cache-Control": "no-store",
                "Pragma": "no-cache",
            ],
            body: .init(data: data)
        )
    }

    private func oauthErrorResponse(error: String, description: String) -> Response {
        let body: [String: Any] = [
            "error": error,
            "error_description": description,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]) else {
            return Response(status: .internalServerError)
        }
        return Response(
            status: .badRequest,
            headers: ["Content-Type": "application/json"],
            body: .init(data: data)
        )
    }

    private func extractBasicAuth(req: Request) -> (String?, String?) {
        guard let authHeader = req.headers.first(name: .authorization),
              authHeader.lowercased().hasPrefix("basic ") else {
            return (nil, nil)
        }
        let encoded = String(authHeader.dropFirst("Basic ".count))
        guard let decoded = Data(base64Encoded: encoded),
              let credString = String(data: decoded, encoding: .utf8) else {
            return (nil, nil)
        }
        let parts = credString.split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return (nil, nil) }
        return (String(parts[0]), String(parts[1]))
    }
}
