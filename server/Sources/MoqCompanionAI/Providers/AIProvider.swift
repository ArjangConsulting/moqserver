/// Protocol for AI provider backends (Ollama, OpenAI, Anthropic, etc.).
public protocol AIProvider: Sendable {
    /// Unique provider identifier (e.g. "ollama", "openai", "anthropic").
    var id: String { get }

    /// Human-readable display name.
    var displayName: String { get }

    /// Whether this provider runs locally or is cloud-hosted.
    var kind: ProviderKind { get }

    /// AI capabilities this provider supports.
    var capabilities: [AICapability] { get }

    /// Check if the provider is currently reachable and configured.
    func checkAvailability() async -> Bool

    /// Validate that the provider's configuration is correct.
    func validateConfig() async -> ValidateConfigResponse

    /// Send a structured prompt and return a raw text completion.
    func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult
}

/// Result from a provider completion call.
public struct AICompletionResult: Sendable {
    public let text: String
    public let model: String
    public let usage: UsageInfo?

    public init(text: String, model: String, usage: UsageInfo? = nil) {
        self.text = text
        self.model = model
        self.usage = usage
    }
}
