import Foundation
import Vapor

/// Mock OAuth2/auth endpoints under `/_auth/*`.
/// Provides a token endpoint that issues mock access tokens for testing.
public struct AuthRouter {
    let config: ServerConfig?

    public init(config: ServerConfig? = nil) {
        self.config = config
    }

    public func registerRoutes(on app: Application) {
        let auth = app.grouped("_auth")

        auth.post("token") { req async throws -> Response in
            try await handleToken(req: req)
        }

        auth.get("authorize") { req async throws -> Response in
            handleAuthorize(req: req)
        }
    }

    private func handleToken(req: Request) async throws -> Response {
        let grantType: String

        if let formGrant = try? req.content.get(String.self, at: "grant_type") {
            grantType = formGrant
        } else {
            return oauthErrorResponse(error: "unsupported_grant_type", description: "Missing grant_type parameter")
        }

        switch grantType {
        case "client_credentials":
            return handleClientCredentials(req: req)
        case "password":
            return handlePasswordGrant(req: req)
        case "authorization_code":
            return handleAuthorizationCodeGrant(req: req)
        case "refresh_token":
            return handleRefreshTokenGrant()
        default:
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
                  clients.contains(where: { $0.clientId == id && $0.clientSecret == secret }) else {
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
                  validCreds.contains(where: { $0.username == u && $0.password == p }) else {
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

        var location = "\(redirectUri)?code=mock-auth-code-\(UUID().uuidString.prefix(8))"
        if let state {
            location += "&state=\(state)"
        }

        return Response(
            status: .found,
            headers: [
                "Location": location,
                "Content-Type": "application/json",
            ],
            body: .init(data: Data(#"{"message":"Redirecting to \#(redirectUri)"}"#.utf8))
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
