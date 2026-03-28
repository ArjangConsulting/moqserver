/// Protocol for validating incoming requests against endpoint rules.
public protocol RequestValidating: Sendable {
    func validate(endpoint: Endpoint, context: RequestContext) -> RequestValidationError?
}
