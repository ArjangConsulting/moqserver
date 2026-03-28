import MoqCore
import Vapor

/// Configures and returns a Vapor Application ready to serve mock endpoints.
public func buildApp(
    store: any MockStoring,
    config: ServerConfig? = nil,
    authValidator: (any AuthValidating)? = nil,
    requestValidator: (any RequestValidating)? = nil,
    hostname: String = "127.0.0.1",
    port: Int = 8080
) async throws -> Application {
    let executable = ProcessInfo.processInfo.arguments.first ?? "moqserver"
    let environment = Environment(name: "development", arguments: [executable])
    let app = try await Application.make(environment)
    app.http.server.configuration.hostname = hostname
    app.http.server.configuration.port = port

    // Use structured error responses for all unhandled errors
    app.middleware = Middlewares()
    app.middleware.use(MockErrorMiddleware())

    let handler = MockHandler(
        store: store,
        config: config,
        authValidator: authValidator,
        requestValidator: requestValidator
    )

    let authRouter = AuthRouter(config: config)
    authRouter.registerRoutes(on: app)

    let adminHandler = AdminHandler(store: store, config: config)
    let adminRouter = AdminRouter(handler: adminHandler)
    adminRouter.registerRoutes(on: app)

    let router = MockRouter(handler: handler)
    router.registerRoutes(on: app)

    return app
}
