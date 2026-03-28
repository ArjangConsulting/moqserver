/// A request validation failure with a status code and message.
public struct RequestValidationError: Sendable, Equatable {
    public let statusCode: HTTPStatusCode
    public let message: String

    public init(statusCode: HTTPStatusCode, message: String) {
        self.statusCode = statusCode
        self.message = message
    }
}
