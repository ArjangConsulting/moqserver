import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockErrorMiddleware")

/// Middleware that converts thrown errors into structured `ErrorResponse` JSON.
struct MockErrorMiddleware: AsyncMiddleware {
    func respond(to request: Request, chainingTo next: any AsyncResponder) async throws -> Response {
        do {
            return try await next.respond(to: request)
        } catch let error as CancellationError {
            throw error
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
                    code: Self.statusToCode(abortError.status)
                )
                logger.warning(
                    "Request error \(status.code): \(abortError.reason)",
                    metadata: [
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

    /// Stable `code` for an aborted request. Known statuses get their documented code; any other
    /// status gets its reason phrase in snake_case (e.g. `410` → `gone`), so a client can
    /// always tell errors apart without parsing the message. Only a real 500 is `internal_error`.
    static func statusToCode(_ status: HTTPResponseStatus) -> String {
        switch status {
        case .badRequest: return "bad_request"
        case .unauthorized: return "unauthorized"
        case .forbidden: return "forbidden"
        case .notFound: return "not_found"
        case .methodNotAllowed: return "method_not_allowed"
        case .notAcceptable: return "not_acceptable"
        case .conflict: return "conflict"
        case .payloadTooLarge: return "payload_too_large"
        case .unsupportedMediaType: return "unsupported_media_type"
        case .unprocessableEntity: return "unprocessable_entity"
        case .preconditionRequired: return "precondition_required"
        case .tooManyRequests: return "too_many_requests"
        case .internalServerError: return "internal_error"
        case .notImplemented: return "not_implemented"
        case .serviceUnavailable: return "service_unavailable"
        default:
            let words = status.reasonPhrase.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            guard !words.isEmpty else { return status.code >= 500 ? "internal_error" : "http_\(status.code)" }
            return words.joined(separator: "_")
        }
    }
}
