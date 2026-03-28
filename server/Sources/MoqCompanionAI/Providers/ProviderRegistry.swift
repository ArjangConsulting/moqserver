/// Thread-safe registry of AI providers.
public actor ProviderRegistry {
    private var providers: [String: any AIProvider] = [:]

    public init(providers: [String: any AIProvider] = [:]) {
        self.providers = providers
    }

    /// Register a provider.
    public func register(_ provider: any AIProvider) {
        providers[provider.id] = provider
    }

    /// Look up a provider by id.
    public func provider(for id: String) -> (any AIProvider)? {
        providers[id]
    }

    /// List all registered providers with current availability.
    public func listProviders() async -> [ProviderInfo] {
        var result: [ProviderInfo] = []
        for provider in providers.values {
            let available = await provider.checkAvailability()
            result.append(ProviderInfo(
                id: provider.id,
                displayName: provider.displayName,
                kind: provider.kind,
                available: available,
                capabilities: provider.capabilities
            ))
        }
        return result.sorted { $0.id < $1.id }
    }

    /// Build a registry from configuration, registering all configured providers.
    public static func from(config: ProviderConfig) -> ProviderRegistry {
        var providers: [String: any AIProvider] = [:]

        // Ollama is always registered (local, no key needed)
        let ollamaConfig = config.ollama ?? OllamaConfig()
        let ollama = OllamaProvider(config: ollamaConfig)
        providers[ollama.id] = ollama

        if let openaiConfig = config.openai {
            let openai = OpenAIProvider(config: openaiConfig)
            providers[openai.id] = openai
        }
        if let anthropicConfig = config.anthropic {
            let anthropic = AnthropicProvider(config: anthropicConfig)
            providers[anthropic.id] = anthropic
        }

        return ProviderRegistry(providers: providers)
    }
}
