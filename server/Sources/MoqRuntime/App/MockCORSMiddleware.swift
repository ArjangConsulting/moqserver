import Logging
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockCORSMiddleware")

/// Permissive CORS for browser clients (`serve --cors`). Any origin is allowed and echoed back
/// with credentials, preflight requests are answered directly, and the request's own
/// `Access-Control-Request-Headers` are reflected so custom headers such as `X-Mock-Variant`,
/// `X-Mock-Session` or a bundle's API-key header pass without a fixed allow-list.
///
/// Off by default: it also opens `/_admin` to any page the browser loads, so enable it only on
/// a machine you trust or together with admin credentials.
struct MockCORSMiddleware: AsyncMiddleware {
    static let allowedMethods = "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS"
    static let preflightMaxAge = "600"

    func respond(to request: Request, chainingTo next: any AsyncResponder) async throws -> Response {
        guard let origin = request.headers.first(name: .origin) else {
            return try await next.respond(to: request)
        }

        let isPreflight =
            request.method == .OPTIONS && request.headers.first(name: .accessControlRequestMethod) != nil
        let response: Response
        if isPreflight {
            logger.debug("Answering CORS preflight for \(request.url.path) from \(origin)")
            response = Response(status: .noContent)
            response.headers.replaceOrAdd(name: .accessControlAllowMethods, value: Self.allowedMethods)
            if let requested = request.headers.first(name: .accessControlRequestHeaders) {
                response.headers.replaceOrAdd(name: .accessControlAllowHeaders, value: requested)
            }
            response.headers.replaceOrAdd(name: .accessControlMaxAge, value: Self.preflightMaxAge)
        } else {
            response = try await next.respond(to: request)
            // `*` is literal, not a wildcard, on credentialed requests, so name each header.
            let exposed = Set(response.headers.map { $0.name.lowercased() }).sorted()
            if !exposed.isEmpty {
                response.headers.replaceOrAdd(name: .accessControlExpose, value: exposed.joined(separator: ", "))
            }
        }
        response.headers.replaceOrAdd(name: .accessControlAllowOrigin, value: origin)
        response.headers.replaceOrAdd(name: .accessControlAllowCredentials, value: "true")
        response.headers.add(name: .vary, value: "Origin")
        return response
    }
}
