import MoqCore
import Vapor

/// Middleware that converts thrown errors into structured `ErrorResponse` JSON.
struct MockErrorMiddleware: Middleware {
    func respond(to request: Request, chainingTo next: any Responder) -> EventLoopFuture<Response> {
        next.respond(to: request).flatMapError { error in
            let status: HTTPResponseStatus
            let body: ErrorResponse

            if let abortError = error as? AbortError {
                status = abortError.status
                body = ErrorResponse(
                    error: abortError.reason,
                    code: statusToCode(abortError.status)
                )
            } else {
                status = .internalServerError
                body = ErrorResponse(
                    error: "An unexpected error occurred.",
                    code: "internal_error"
                )
            }

            let response = Response(status: status)
            response.headers.contentType = .json
            response.body = .init(data: body.jsonData())
            return request.eventLoop.makeSucceededFuture(response)
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
