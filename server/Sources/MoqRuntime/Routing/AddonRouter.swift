import Foundation
import Logging
import MoqAddonKit
import Vapor

private let logger = Logger(label: "moqserver.runtime.AddonRouter")

/// Mounts each active add-on's H1 routes under `/_addons/<id>/…`.
struct AddonRouter: Sendable {
    let addons: ActiveAddons

    /// Upper bound on a buffered request body for add-on routes.
    private static let maxBodySize: ByteCount = "1mb"

    func registerRoutes(on app: Application) {
        for addon in addons.addons {
            let id = type(of: addon).id
            let prefixes = [["_addons", id]] + addon.compatibilityRoutePrefixes
            for route in addon.routes {
                for prefix in prefixes {
                    register(route, under: prefix, on: app)
                }
            }
        }
    }

    private func register(_ route: AddonRoute, under prefix: [String], on app: Application) {
        let components = (prefix + route.path).map(PathComponent.init(stringLiteral:))
        let prefixPath = "/" + prefix.joined(separator: "/")
        logger.info("Registering add-on route \(route.method) \(prefixPath)/\(route.path.joined(separator: "/"))")
        app.on(HTTPMethod(rawValue: route.method), components, body: .collect(maxSize: Self.maxBodySize)) {
            req async -> Response in
            let response = await route.handler(Self.addonRequest(from: req, prefix: prefixPath))
            return Self.vaporResponse(from: response)
        }
    }

    static func addonRequest(from req: Request, prefix: String) -> AddonHTTPRequest {
        let fullPath = req.url.path
        let relative = fullPath.hasPrefix(prefix) ? String(fullPath.dropFirst(prefix.count)) : fullPath
        var query: [String: String] = [:]
        for item in URLComponents(string: req.url.string)?.queryItems ?? [] {
            query[item.name] = item.value ?? ""
        }
        var headers: [String: String] = [:]
        for (name, value) in req.headers {
            headers[name] = value
        }
        let body = req.body.data.map { Data(buffer: $0) }
        return AddonHTTPRequest(
            method: req.method.rawValue, path: relative.isEmpty ? "/" : relative, query: query, headers: headers,
            body: body)
    }

    static func vaporResponse(from response: AddonHTTPResponse) -> Response {
        var headers = HTTPHeaders()
        for (name, value) in response.headers.sorted(by: { $0.key < $1.key }) {
            headers.add(name: name, value: value)
        }
        return Response(
            status: HTTPResponseStatus(statusCode: response.status),
            headers: headers,
            body: response.body.map { .init(data: $0) } ?? .empty)
    }
}
