import Logging
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockRouter")

/// Registers catch-all routes for all HTTP methods, delegating to MockHandler.
public struct MockRouter {
    let handler: MockHandler

    public init(handler: MockHandler) {
        self.handler = handler
    }

    public func registerRoutes(on app: Application) {
        let methods: [HTTPMethod] = [.GET, .POST, .PUT, .PATCH, .DELETE, .HEAD, .OPTIONS, .TRACE, .CONNECT]
        logger.info("Registering catch-all mock routes for \(methods.count) HTTP methods")

        for method in methods {
            app.on(method, .catchall) { req async throws -> Response in
                try await handler.handle(req: req)
            }
        }
    }
}
