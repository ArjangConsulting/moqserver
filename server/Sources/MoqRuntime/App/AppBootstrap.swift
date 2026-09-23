import Logging
import MoqAddonKit
import MoqAddons
import MoqCore
import Vapor

private let bootstrapLogger = Logger(label: "moqserver.runtime.AppBootstrap")

/// Configures and returns a Vapor Application ready to serve mock endpoints.
public func buildApp(
    store: any MockStoring,
    config: ServerConfig? = nil,
    authValidator: (any AuthValidating)? = nil,
    requestValidator: (any RequestValidating)? = nil,
    addons: ActiveAddons = .none,
    hostname: String = "127.0.0.1",
    port: Int = 8080
) async throws -> Application {
    bootstrapLogger.info("Building app on \(hostname):\(port)")
    let executable = ProcessInfo.processInfo.arguments.first ?? "moqserver"
    let environment = Environment(name: "development", arguments: [executable])
    let app = try await Application.make(environment)
    app.http.server.configuration.hostname = hostname
    app.http.server.configuration.port = port

    // Use structured error responses for all unhandled errors
    app.middleware = Middlewares()
    app.middleware.use(MockErrorMiddleware())
    bootstrapLogger.debug("Registered MockErrorMiddleware")

    // oauth-mock is always active (it replaced the built-in /_auth router), configured from the
    // server config rather than the bundle, unless the caller already supplied one.
    let addons =
        addons[OAuthMockAddon.id] == nil
        ? ActiveAddons(addons.addons + [OAuthMockAddon(config: config?.oauthMockConfig ?? OAuthMockConfig())])
        : addons

    let handler = MockHandler(
        store: store,
        config: config,
        authValidator: authValidator,
        requestValidator: requestValidator,
        addons: addons
    )

    app.get("health") { _ async -> [String: String] in
        ["status": "ready"]
    }

    AddonRouter(addons: addons).registerRoutes(on: app)

    let adminHandler = AdminHandler(store: store, config: config)
    let adminRouter = AdminRouter(handler: adminHandler)
    adminRouter.registerRoutes(on: app)

    let endpoints = await store.allEndpoints()
    let router = MockRouter(handler: handler, endpoints: endpoints)
    router.registerRoutes(on: app)

    bootstrapLogger.info("App configured: auth, add-on, admin, and mock routes registered")
    return app
}
