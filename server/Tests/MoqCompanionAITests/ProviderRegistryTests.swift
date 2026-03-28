import Testing
@testable import MoqCompanionAI

@Suite("ProviderRegistry Tests")
struct ProviderRegistryTests {
    @Test("Register and look up a provider")
    func registerAndLookup() async {
        let registry = ProviderRegistry()
        let provider = StubProvider(id: "test", displayName: "Test", kind: .local, available: true)
        await registry.register(provider)

        let found = await registry.provider(for: "test")
        #expect(found != nil)
        #expect(found?.id == "test")
    }

    @Test("Lookup returns nil for unknown provider")
    func lookupUnknown() async {
        let registry = ProviderRegistry()
        let found = await registry.provider(for: "nonexistent")
        #expect(found == nil)
    }

    @Test("List providers returns all registered with availability")
    func listProviders() async {
        let registry = ProviderRegistry()
        await registry.register(StubProvider(id: "a", displayName: "A", kind: .local, available: true))
        await registry.register(StubProvider(id: "b", displayName: "B", kind: .hosted, available: false))

        let list = await registry.listProviders()
        #expect(list.count == 2)
        #expect(list[0].id == "a")
        #expect(list[0].available == true)
        #expect(list[1].id == "b")
        #expect(list[1].available == false)
    }

    @Test("Build registry from config registers Ollama by default")
    func fromConfig() async {
        let config = ProviderConfig()
        let registry = ProviderRegistry.from(config: config)

        let providers = await registry.listProviders()
        #expect(providers.contains { $0.id == "ollama" })
    }

    @Test("Build registry from config with OpenAI and Anthropic")
    func fromConfigWithHosted() async {
        let config = ProviderConfig(
            openai: OpenAIConfig(apiKey: "test-key"),
            anthropic: AnthropicConfig(apiKey: "test-key")
        )
        let registry = ProviderRegistry.from(config: config)

        let providers = await registry.listProviders()
        #expect(providers.count == 3)
        #expect(providers.contains { $0.id == "ollama" })
        #expect(providers.contains { $0.id == "openai" })
        #expect(providers.contains { $0.id == "anthropic" })
    }
}

// MARK: - Test Doubles

struct StubProvider: AIProvider {
    let id: String
    let displayName: String
    let kind: ProviderKind
    let available: Bool
    var capabilities: [AICapability] = [.analyzeSpec, .generateVariants, .refineProject]

    func checkAvailability() async -> Bool { available }

    func validateConfig() async -> ValidateConfigResponse {
        ValidateConfigResponse(valid: available)
    }

    func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult {
        AICompletionResult(text: "stub response", model: model ?? "stub-model")
    }
}
