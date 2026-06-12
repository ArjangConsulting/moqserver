import Foundation

import Logging

private let logger = Logger(label: "moqserver.core.AuthValidator")

/// Default implementation of auth validation logic.
/// Unit-testable without any web framework dependency.
public struct AuthValidator: AuthValidating {
    private let config: AuthConfig?

    public init(config: AuthConfig? = nil) {
        self.config = config
    }

    public func evaluate(_ requirement: AuthRequirement, context: AuthContext) -> AuthOutcome {
        logger.debug("Evaluating auth requirement: \(requirement)")
        switch requirement {
        case .none:
            return .allowed

        case .allOf(let requirements):
            for req in requirements {
                let result = evaluate(req, context: context)
                switch result {
                case .allowed:
                    continue
                case .unauthorized, .forbidden:
                    logger.debug("Auth allOf check failed on sub-requirement: \(req)")
                    return result
                }
            }
            return .allowed

        case .anyOf(let requirements):
            var firstFailure: AuthOutcome?
            for req in requirements {
                let result = evaluate(req, context: context)
                if case .allowed = result {
                    return .allowed
                }
                if firstFailure == nil {
                    firstFailure = result
                }
            }
            logger.debug("Auth anyOf check failed, no alternative matched")
            return firstFailure ?? .unauthorized(message: "Authentication required", wwwAuthenticate: nil)

        case .bearer:
            guard let authHeader = context.authorizationHeader,
                  authHeader.lowercased().hasPrefix("bearer ") else {
                logger.debug("Bearer token missing from request")
                return .unauthorized(
                    message: "Bearer token required",
                    wwwAuthenticate: "Bearer realm=\"mock-server\""
                )
            }
            let token = String(authHeader.dropFirst("Bearer ".count))
            if let validTokens = config?.bearerTokens, !validTokens.isEmpty,
               !validTokens.contains(where: { SecureCompare.equals(token, $0) }) {
                logger.debug("Invalid bearer token provided")
                return .unauthorized(
                    message: "Invalid bearer token",
                    wwwAuthenticate: "Bearer realm=\"mock-server\", error=\"invalid_token\""
                )
            }
            return .allowed

        case .basic:
            guard let authHeader = context.authorizationHeader,
                  authHeader.lowercased().hasPrefix("basic ") else {
                logger.debug("Basic auth header missing from request")
                return .unauthorized(
                    message: "Basic auth required",
                    wwwAuthenticate: "Basic realm=\"mock-server\""
                )
            }
            let encoded = String(authHeader.dropFirst("Basic ".count))
            if let validCreds = config?.basicCredentials, !validCreds.isEmpty {
                guard let decoded = Data(base64Encoded: encoded),
                      let credString = String(data: decoded, encoding: .utf8) else {
                    logger.debug("Invalid basic auth encoding")
                    return .unauthorized(
                        message: "Invalid basic auth encoding",
                        wwwAuthenticate: "Basic realm=\"mock-server\""
                    )
                }
                let parts = credString.split(separator: ":", maxSplits: 1)
                guard parts.count == 2,
                      validCreds.contains(where: {
                          SecureCompare.equals(String(parts[0]), $0.username) &&
                          SecureCompare.equals(String(parts[1]), $0.password)
                      }) else {
                    logger.debug("Invalid basic auth credentials")
                    return .unauthorized(
                        message: "Invalid credentials",
                        wwwAuthenticate: "Basic realm=\"mock-server\""
                    )
                }
            }
            return .allowed

        case .apiKey(let headerName):
            // API key validation requires the header value from the request.
            // For now, the AuthContext only carries the Authorization header.
            // The runtime layer should extract the specific header and pass it via a richer context,
            // or this case should be handled at the runtime level.
            // We keep the protocol-level contract: if authorizationHeader is set for api-key,
            // treat it as the api-key header value.
            guard let value = context.authorizationHeader, !value.isEmpty else {
                logger.debug("API key missing for header '\(headerName)'")
                return .unauthorized(
                    message: "API key required in header '\(headerName)'",
                    wwwAuthenticate: nil
                )
            }
            if let validKeys = config?.apiKeys, let expectedKey = validKeys[headerName],
               !SecureCompare.equals(value, expectedKey) {
                logger.debug("Invalid API key for header '\(headerName)'")
                return .unauthorized(message: "Invalid API key", wwwAuthenticate: nil)
            }
            return .allowed

        case .oauth2(let requiredScopes), .openIdConnect(let requiredScopes):
            guard let authHeader = context.authorizationHeader,
                  authHeader.lowercased().hasPrefix("bearer ") else {
                logger.debug("OAuth2 access token missing from request")
                let scopeStr = requiredScopes.isEmpty ? "" : ", scope=\"\(requiredScopes.joined(separator: " "))\""
                return .unauthorized(
                    message: "OAuth2 access token required",
                    wwwAuthenticate: "Bearer realm=\"mock-server\"\(scopeStr)"
                )
            }

            let token = String(authHeader.dropFirst("Bearer ".count))
            let validTokens = config?.oauth2Tokens ?? config?.bearerTokens
            if let validTokens, !validTokens.isEmpty,
               !validTokens.contains(where: { SecureCompare.equals(token, $0) }) {
                logger.debug("Invalid or expired OAuth2 access token")
                let scopeStr = requiredScopes.isEmpty ? "" : ", scope=\"\(requiredScopes.joined(separator: " "))\""
                return .unauthorized(
                    message: "Invalid or expired access token",
                    wwwAuthenticate: "Bearer realm=\"mock-server\", error=\"invalid_token\"\(scopeStr)"
                )
            }

            if !requiredScopes.isEmpty {
                guard let scopeMap = config?.oauth2TokenScopes else {
                    logger.debug("Insufficient scope: no scope map configured")
                    let scopeStr = requiredScopes.joined(separator: " ")
                    return .forbidden(
                        message: "Insufficient scope",
                        wwwAuthenticate: "Bearer realm=\"mock-server\", error=\"insufficient_scope\", scope=\"\(scopeStr)\""
                    )
                }

                let tokenScopes = Set(scopeMap[token] ?? [])
                let neededScopes = Set(requiredScopes)
                guard neededScopes.isSubset(of: tokenScopes) else {
                    logger.debug("Insufficient scope: needed \(neededScopes), token has \(tokenScopes)")
                    let scopeStr = requiredScopes.joined(separator: " ")
                    return .forbidden(
                        message: "Insufficient scope",
                        wwwAuthenticate: "Bearer realm=\"mock-server\", error=\"insufficient_scope\", scope=\"\(scopeStr)\""
                    )
                }
            }

            return .allowed
        }
    }
}
