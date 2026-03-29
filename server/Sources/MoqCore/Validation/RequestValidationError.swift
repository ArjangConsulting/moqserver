/// A request validation failure with a status code and message.
public enum RequestValidationErrorCode: String, Sendable, Equatable {
    case missingRequiredHeader = "missing_required_header"
    case headerValueMismatch = "header_value_mismatch"
    case missingRequiredQueryParam = "missing_required_query_param"
    case queryParamValueMismatch = "query_param_value_mismatch"
    case missingRequiredCookie = "missing_required_cookie"
    case cookieValueMismatch = "cookie_value_mismatch"
    case missingRequestBody = "missing_request_body"
    case unsupportedContentType = "unsupported_content_type"
}

public struct RequestValidationError: Sendable, Equatable {
    public let statusCode: HTTPStatusCode
    public let code: RequestValidationErrorCode
    public let message: String

    public init(statusCode: HTTPStatusCode, code: RequestValidationErrorCode, message: String) {
        self.statusCode = statusCode
        self.code = code
        self.message = message
    }
}
