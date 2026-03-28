import Vapor

/// Build and configure the companion Vapor application.
public func buildCompanionApp(
    registry: ProviderRegistry,
    redactionEngine: RedactionEngine = RedactionEngine(),
    hostname: String = "127.0.0.1",
    port: Int = 8081
) async throws -> Application {
    let executable = CommandLine.arguments.first ?? "moqserver"
    let environment = Environment(name: "development", arguments: [executable])
    let app = try await Application.make(environment)

    app.http.server.configuration.hostname = hostname
    app.http.server.configuration.port = port

    // Use structured error responses
    app.middleware = Middlewares()
    app.middleware.use(CompanionErrorMiddleware())

    let handler = CompanionHandler(registry: registry, redactionEngine: redactionEngine)
    let router = CompanionRouter(handler: handler)
    router.registerRoutes(on: app)

    return app
}

/// Middleware that converts errors to CompanionErrorResponse JSON.
struct CompanionErrorMiddleware: Middleware {
    func respond(to request: Request, chainingTo next: any Responder) -> EventLoopFuture<Response> {
        next.respond(to: request).flatMapError { error in
            let status: HTTPResponseStatus
            let body: CompanionErrorResponse

            if let companionError = error as? CompanionAbortError {
                status = companionError.status
                body = companionError.body
            } else if let abortError = error as? AbortError {
                status = abortError.status
                body = CompanionErrorResponse(
                    code: .internalError,
                    message: abortError.reason
                )
            } else {
                status = .internalServerError
                body = CompanionErrorResponse(
                    code: .internalError,
                    message: "An unexpected error occurred."
                )
            }

            let response = Response(status: status)
            do {
                try response.content.encode(body)
            } catch {
                response.body = .init(string: #"{"error":{"code":"internal_error","message":"Failed to encode error response.","retryable":false}}"#)
                response.headers.contentType = .json
            }
            return request.eventLoop.makeSucceededFuture(response)
        }
    }
}
