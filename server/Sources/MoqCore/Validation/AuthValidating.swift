/// Protocol for evaluating auth requirements against request context.
public protocol AuthValidating: Sendable {
    func evaluate(_ requirement: AuthRequirement, context: AuthContext) -> AuthOutcome
}
