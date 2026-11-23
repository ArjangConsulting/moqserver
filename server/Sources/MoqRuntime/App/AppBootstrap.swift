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
    requireSession: Bool = false,
    requestBodyCaptureLimit: Int = 0,
    captureRequestHeaders: Bool = false,
    redactHeaders: [String] = [],
    maxRequestBodySize: Int = MockRouter.defaultMaxBodySize,
    cors: Bool = false,
    tls: TLSFiles? = nil,
    reloadSource: RuntimeProjectSource? = nil,
    hostname: String = "127.0.0.1",
    port: Int = 8080
) async throws -> Application {
    bootstrapLogger.info("Building app on \(hostname):\(port)")
    let executable = ProcessInfo.processInfo.arguments.first ?? "moqserver"
    let environment = Environment(name: "development", arguments: [executable])
    let app = try await Application.make(environment)
    app.http.server.configuration.hostname = hostname
    app.http.server.configuration.port = port

    if let tls {
        app.http.server.configuration.tlsConfiguration = try tls.serverConfiguration()
        bootstrapLogger.info("TLS enabled", metadata: ["certificate": "\(tls.certificatePath)"])
    }

    // CORS runs outermost so error responses carry the CORS headers too; structured error
    // responses for all unhandled errors come next.
    app.middleware = Middlewares()
    if cors {
        app.middleware.use(MockCORSMiddleware())
        bootstrapLogger.info("CORS enabled for all origins")
    }
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
        addons: addons,
        requireSession: requireSession,
        requestBodyCaptureLimit: requestBodyCaptureLimit,
        captureRequestHeaders: captureRequestHeaders,
        redactHeaders: SensitiveHeaders.normalized(redactHeaders)
    )

    app.get("health") { _ async -> [String: String] in
        ["status": "ready"]
    }

    AddonRouter(addons: addons).registerRoutes(on: app)

    let table = MockRouteTable(endpoints: await store.allEndpoints())
    var reloader: MockReloader?
    if let reloadSource, let root = store as? InMemoryMockStore {
        reloader = MockReloader(store: root, table: table, source: reloadSource)
        app.storage[MockReloaderKey.self] = reloader
    }

    let adminHandler = AdminHandler(store: store, config: config, reloader: reloader)
    let adminRouter = AdminRouter(handler: adminHandler)
    adminRouter.registerRoutes(on: app)

    let router = MockRouter(handler: handler, table: table, maxBodySize: maxRequestBodySize)
    router.registerRoutes(on: app)

    bootstrapLogger.info("App configured: auth, add-on, admin, and mock routes registered")
    return app
}

private struct MockReloaderKey: StorageKey {
    typealias Value = MockReloader
}

extension Application {
    /// Reloads the served project; set when `buildApp` was given a `reloadSource`.
    public var mockReloader: MockReloader? {
        storage[MockReloaderKey.self]
    }
}
