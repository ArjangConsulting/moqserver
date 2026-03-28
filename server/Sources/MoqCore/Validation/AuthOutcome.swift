/// The result of evaluating an auth requirement against a request.
public enum AuthOutcome: Sendable, Equatable {
    case allowed
    case unauthorized(message: String, wwwAuthenticate: String?)
    case forbidden(message: String, wwwAuthenticate: String?)
}
