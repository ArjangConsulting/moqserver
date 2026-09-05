import Logging
import Vapor

private let logger = Logger(label: "moqserver.runtime.AdminRouter")

/// Registers admin API routes under /_admin/*.
/// Must be registered BEFORE catch-all mock routes.
public struct AdminRouter: Sendable {
    let handler: AdminHandler

    public init(handler: AdminHandler) {
        self.handler = handler
    }

    public func registerRoutes(on app: Application) {
        logger.info("Registering admin API routes under /_admin")
        let admin = app.grouped("_admin")
        registerRuntimeRoutes(on: admin)

        admin.get("endpoints") { req async throws -> [EndpointListItem] in
            try await handler.scoped(req: req).listEndpoints(req: req)
        }

        admin.get("endpoints", ":method", "**") { req async throws -> EndpointDetail in
            try await handler.scoped(req: req).getEndpoint(req: req)
        }

        admin.put("endpoints", ":method", "**") { req async throws -> MessageResponse in
            try await handler.scoped(req: req).setVariant(req: req)
        }

        admin.delete("endpoints", ":method", "**") { req async throws -> MessageResponse in
            try await handler.scoped(req: req).resetVariant(req: req)
        }
    }
}
