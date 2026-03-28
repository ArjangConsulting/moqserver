/// Default settings for the project, applied to all endpoints unless overridden.
public struct ProjectDefaults: Codable, Sendable, Equatable {
    /// Default additional delay in milliseconds.
    public let delayMs: Int
    /// Default auth configuration.
    public let auth: ProjectAuthConfig
    /// Default network simulation.
    public let network: NetworkBehavior

    public init(delayMs: Int = 0, auth: ProjectAuthConfig, network: NetworkBehavior) {
        self.delayMs = delayMs
        self.auth = auth
        self.network = network
    }

    enum CodingKeys: String, CodingKey {
        case delayMs = "delay_ms"
        case auth, network
    }
}
