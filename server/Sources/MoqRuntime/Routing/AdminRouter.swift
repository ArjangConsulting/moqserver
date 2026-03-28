import Vapor

/// Registers admin API routes under /_admin/*.
/// Must be registered BEFORE catch-all mock routes.
public struct AdminRouter {
    let handler: AdminHandler

    public init(handler: AdminHandler) {
        self.handler = handler
    }

    public func registerRoutes(on app: Application) {
        let admin = app.grouped("_admin")

        admin.get("endpoints") { req async throws -> [EndpointListItem] in
            try await handler.listEndpoints(req: req)
        }

        admin.get("endpoints", ":method", "**") { req async throws -> EndpointDetail in
            try await handler.getEndpoint(req: req)
        }

        admin.put("endpoints", ":method", "**") { req async throws -> MessageResponse in
            try await handler.setVariant(req: req)
        }

        admin.delete("endpoints", ":method", "**") { req async throws -> MessageResponse in
            try await handler.resetVariant(req: req)
        }
    }
}
