import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockErrorMiddleware")

/// Middleware that converts thrown errors into structured `ErrorResponse` JSON.
struct MockErrorMiddleware: AsyncMiddleware {
    func respond(to request: Request, chainingTo next: any AsyncResponder) async throws -> Response {
        do {
            return try await next.respond(to: request)
        } catch {
            let status: HTTPResponseStatus
            let body: ErrorResponse
            var headers = HTTPHeaders()

            if let abortError = error as? AbortError {
                status = abortError.status
                // Preserve headers attached to the abort (e.g. WWW-Authenticate challenges).
                headers = abortError.headers
                body = ErrorResponse(
                    error: abortError.reason,
                    code: statusToCode(abortError.status)
                )
                logger.warning("Request error \(status.code): \(abortError.reason)", metadata: [
                    "path": "\(request.url.path)",
                    "status": "\(status.code)",
                ])
            } else {
                status = .internalServerError
                body = ErrorResponse(
                    error: "An unexpected error occurred.",
                    code: "internal_error"
                )
                logger.error("Unexpected error handling \(request.method) \(request.url.path): \(error)")
            }

            let response = Response(status: status, headers: headers)
            response.headers.contentType = .json
            response.body = .init(data: body.jsonData())
            return response
        }
    }

    private func statusToCode(_ status: HTTPResponseStatus) -> String {
        switch status {
        case .badRequest: return "bad_request"
        case .unauthorized: return "unauthorized"
        case .forbidden: return "forbidden"
        case .notFound: return "not_found"
        case .unsupportedMediaType: return "unsupported_media_type"
        default: return "internal_error"
        }
    }
}
