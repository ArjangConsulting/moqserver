import Vapor

/// Normalized error codes per COMPANION_API.md
public enum CompanionErrorCode: String, Codable, Sendable {
    case providerUnavailable = "provider_unavailable"
    case providerAuthInvalid = "provider_auth_invalid"
    case providerTimeout = "provider_timeout"
    case requestRejectedRedactionPolicy = "request_rejected_redaction_policy"
    case requestTooLarge = "request_too_large"
    case invalidRequestShape = "invalid_request_shape"
    case internalError = "internal_error"
}

/// Structured error response returned by all companion endpoints.
public struct CompanionErrorResponse: Content, Sendable {
    public let error: CompanionErrorDetail

    public init(code: CompanionErrorCode, message: String, retryable: Bool = false, details: [String: String]? = nil) {
        self.error = CompanionErrorDetail(code: code, message: message, retryable: retryable, details: details)
    }
}

public struct CompanionErrorDetail: Content, Sendable {
    public let code: CompanionErrorCode
    public let message: String
    public let retryable: Bool
    public let details: [String: String]?
}

/// Abort error that serializes as CompanionErrorResponse.
public struct CompanionAbortError: AbortError {
    public let status: HTTPResponseStatus
    public let body: CompanionErrorResponse

    public var reason: String { body.error.message }

    public init(status: HTTPResponseStatus, code: CompanionErrorCode, message: String, retryable: Bool = false, details: [String: String]? = nil) {
        self.status = status
        self.body = CompanionErrorResponse(code: code, message: message, retryable: retryable, details: details)
    }
}
