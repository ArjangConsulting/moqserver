import Logging
import MoqCore
import NIOConcurrencyHelpers
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockRouter")

/// Routes mock requests. Vapor only sees catch-all routes (registered after the health, add-on
/// and admin routes, which therefore take precedence); each catch-all dispatches through a
/// `MockRouteTable`, which reload can swap without re-registering Vapor routes.
public struct MockRouter: Sendable {
    /// Default upper bound on a buffered mock request body (Vapor's own default is 16 KB).
    public static let defaultMaxBodySize = 10 * 1024 * 1024

    let handler: MockHandler
    let table: MockRouteTable
    let maxBodySize: Int

    public init(handler: MockHandler, table: MockRouteTable, maxBodySize: Int = MockRouter.defaultMaxBodySize) {
        self.handler = handler
        self.table = table
        self.maxBodySize = maxBodySize
    }

    public init(handler: MockHandler, endpoints: [Endpoint], maxBodySize: Int = MockRouter.defaultMaxBodySize) {
        self.init(handler: handler, table: MockRouteTable(endpoints: endpoints), maxBodySize: maxBodySize)
    }

    /// HEAD is absent on purpose: with no HEAD route, Vapor forwards HEAD to the GET catch-all
    /// and strips the body from the response.
    static let catchallMethods: [HTTPMethod] = [.GET, .POST, .PUT, .PATCH, .DELETE, .OPTIONS, .TRACE, .CONNECT]

    public func registerRoutes(on app: Application) {
        let body = HTTPBodyStreamStrategy.collect(maxSize: ByteCount(value: maxBodySize))
        for method in Self.catchallMethods {
            app.on(method, .catchall, body: body) { req async throws -> Response in
                try await dispatch(req)
            }
        }
        logger.info("Registered mock dispatch (max request body \(maxBodySize) bytes)")
    }

    private func dispatch(_ req: Request) async throws -> Response {
        switch table.target(method: req.method.rawValue, path: req.url.path) {
        case .endpoint(let key):
            return try await handler.handle(req: req, matchedKey: key)
        case .options(let allow):
            return Response(status: .noContent, headers: ["Allow": allow])
        case .methodNotAllowed(let allow):
            return try await handler.handleMethodNotAllowed(req: req, allow: allow)
        case nil:
            return try await handler.handleNotFound(req: req)
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

/// The mock route trie, swappable at runtime. Beyond the declared endpoints, every known path
/// also answers:
/// - `HEAD` via its `GET` endpoint,
/// - `OPTIONS` with `204` and an `Allow` header, unless the bundle declares `OPTIONS` itself,
/// - any other method with `405 method_not_allowed` and an `Allow` header.
public final class MockRouteTable: Sendable {
    public enum Target: Sendable, Equatable {
        case endpoint(EndpointKey)
        case options(allow: String)
        case methodNotAllowed(allow: String)
    }

    /// A built trie is never mutated again, so reading one outside the lock is safe; the box only
    /// guards which trie is current.
    private struct Snapshot: @unchecked Sendable {
        let router: TrieRouter<Target>
    }

    private let current: NIOLockedValueBox<Snapshot>

    public init(endpoints: [Endpoint]) {
        current = NIOLockedValueBox(Snapshot(router: Self.build(endpoints)))
    }

    /// Atomically replaces the routes; requests already dispatched finish on the old ones.
    public func replace(with endpoints: [Endpoint]) {
        let snapshot = Snapshot(router: Self.build(endpoints))
        current.withLockedValue { $0 = snapshot }
    }

    public func target(method: String, path: String) -> Target? {
        let router = current.withLockedValue { $0.router }
        // Same splitting as Vapor's DefaultResponder, so matching behaves as it did when each
        // endpoint was its own Vapor route.
        let components = path.split(separator: "/").map(String.init)
        var parameters = Parameters()
        if let target = router.route(path: [method] + components, parameters: &parameters) {
            return target
        }
        guard method == HTTPMethod.HEAD.rawValue else { return nil }
        return router.route(path: [HTTPMethod.GET.rawValue] + components, parameters: &parameters)
    }

    private static let fallbackMethods: [HTTPMethod] = [.GET, .POST, .PUT, .PATCH, .DELETE, .OPTIONS, .TRACE, .CONNECT]

    private static func build(_ endpoints: [Endpoint]) -> TrieRouter<Target> {
        let router = TrieRouter(Target.self, logger: logger)
        var registered: Set<String> = []
        var templates: [String: (components: [PathComponent], methods: Set<String>)] = [:]
        var templateOrder: [String] = []

        for endpoint in endpoints {
            let key = endpoint.key
            let method = HTTPMethod(rawValue: key.method.rawValue).rawValue
            guard registered.insert("\(method) \(key.path)").inserted else { continue }
            let components = MockRouter.pathComponents(for: key.path)
            router.register(.endpoint(key), at: [.constant(method)] + components)

            let template = templateIdentity(components)
            if templates[template] == nil {
                templateOrder.append(template)
                templates[template] = (components, [])
            }
            templates[template]?.methods.insert(method)
        }

        for template in templateOrder {
            guard let route = templates[template] else { continue }
            var allowed = route.methods
            if allowed.contains(HTTPMethod.GET.rawValue) { allowed.insert(HTTPMethod.HEAD.rawValue) }
            allowed.insert(HTTPMethod.OPTIONS.rawValue)
            let allow = allowed.sorted().joined(separator: ", ")
            for method in fallbackMethods where !route.methods.contains(method.rawValue) {
                let target: Target = method == .OPTIONS ? .options(allow: allow) : .methodNotAllowed(allow: allow)
                router.register(target, at: [.constant(method.rawValue)] + route.components)
            }
        }
        logger.debug("Built mock route table for \(registered.count) route(s)")
        return router
    }

    /// Route identity with parameter names erased, so `/users/{id}` and `/users/{userId}` (two
    /// methods on one path) share one `Allow` set.
    private static func templateIdentity(_ components: [PathComponent]) -> String {
        components.map { component -> String in
            if case .parameter = component { return ":" }
            return component.description
        }.joined(separator: "/")
    }
}
