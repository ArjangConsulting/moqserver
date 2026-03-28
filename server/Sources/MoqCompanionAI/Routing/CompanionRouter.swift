import Vapor

/// Registers all companion API routes.
public struct CompanionRouter: Sendable {
    private let handler: CompanionHandler

    public init(handler: CompanionHandler) {
        self.handler = handler
    }

    public func registerRoutes(on app: Application) {
        app.get("health") { req async throws in
            try await handler.health(req: req)
        }

        let ai = app.grouped("ai")

        ai.get("providers") { req async throws in
            try await handler.providers(req: req)
        }

        ai.post("validate-config") { req async throws in
            try await handler.validateConfig(req: req)
        }

        ai.post("analyze-spec") { req async throws in
            try await handler.analyzeSpec(req: req)
        }

        ai.post("generate-variants") { req async throws in
            try await handler.generateVariants(req: req)
        }

        ai.post("refine-project") { req async throws in
            try await handler.refineProject(req: req)
        }
    }
}
