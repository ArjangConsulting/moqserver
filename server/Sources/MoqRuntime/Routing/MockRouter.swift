import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockRouter")

/// Registers per-endpoint Vapor routes and a catch-all fallback for unmapped paths.
/// Path parameters in `{param}` OpenAPI style are converted to Vapor's `:param` syntax,
/// so Vapor's own router handles path matching natively — no regex needed.
public struct MockRouter {
    let handler: MockHandler
    let endpoints: [Endpoint]

    public init(handler: MockHandler, endpoints: [Endpoint]) {
        self.handler = handler
        self.endpoints = endpoints
    }

    public func registerRoutes(on app: Application) {
        logger.info("Registering \(endpoints.count) endpoint route(s)")

        for endpoint in endpoints {
            let vaporComponents = MockRouter.pathComponents(for: endpoint.key.path)
            let key = endpoint.key
            let method = HTTPMethod(rawValue: key.method.rawValue)

            app.on(method, vaporComponents) { req async throws -> Response in
                try await handler.handle(req: req, matchedKey: key)
            }
            logger.debug("Registered \(key.method.rawValue) \(key.path)")
        }

        // Catch-all fallback: returns a structured 404 for any path not matched above.
        let catchallMethods: [HTTPMethod] = [.GET, .POST, .PUT, .PATCH, .DELETE, .HEAD, .OPTIONS, .TRACE, .CONNECT]
        for method in catchallMethods {
            app.on(method, .catchall) { req async throws -> Response in
                try await handler.handleNotFound(req: req)
            }
        }
    }

    // MARK: - Helpers

    /// Converts an OpenAPI-style path template (e.g. `/users/{id}/orders`) into
    /// Vapor path components (e.g. `["users", ":id", "orders"]`).
    static func pathComponents(for templatePath: String) -> [PathComponent] {
        templatePath
            .split(separator: "/", omittingEmptySubsequences: true)
            .map { segment -> PathComponent in
                let s = String(segment)
                if s.hasPrefix("{") && s.hasSuffix("}") {
                    let name = String(s.dropFirst().dropLast())
                    return .parameter(name)
                }
                return .constant(s)
            }
    }
}
