import Foundation
import Testing
@testable import MoqCompanionAI

@Suite("ProviderConfig Tests")
struct ProviderConfigTests {
    // MARK: - Individual Config Structs

    @Test("OllamaConfig defaults")
    func ollamaConfigDefaults() {
        let config = OllamaConfig()
        #expect(config.baseURL == "http://localhost:11434")
        #expect(config.defaultModel == "llama3.1")
    }

    @Test("OllamaConfig custom values")
    func ollamaConfigCustom() {
        let config = OllamaConfig(baseURL: "http://myserver:11434", defaultModel: "mistral")
        #expect(config.baseURL == "http://myserver:11434")
        #expect(config.defaultModel == "mistral")
    }

    @Test("OpenAIConfig defaults")
    func openAIConfigDefaults() {
        let config = OpenAIConfig(apiKey: "sk-test")
        #expect(config.apiKey == "sk-test")
        #expect(config.baseURL == nil)
        #expect(config.defaultModel == "gpt-4o")
    }

    @Test("OpenAIConfig custom values")
    func openAIConfigCustom() {
        let config = OpenAIConfig(apiKey: "sk-test", baseURL: "https://custom.api.com", defaultModel: "gpt-3.5-turbo")
        #expect(config.baseURL == "https://custom.api.com")
        #expect(config.defaultModel == "gpt-3.5-turbo")
    }

    @Test("AnthropicConfig defaults")
    func anthropicConfigDefaults() {
        let config = AnthropicConfig(apiKey: "sk-ant-test")
        #expect(config.apiKey == "sk-ant-test")
        #expect(config.baseURL == nil)
        #expect(config.defaultModel == "claude-sonnet-4-6")
    }

    @Test("AnthropicConfig custom values")
    func anthropicConfigCustom() {
        let config = AnthropicConfig(apiKey: "key", baseURL: "https://custom.anthropic.com", defaultModel: "claude-haiku-4-5-20251001")
        #expect(config.baseURL == "https://custom.anthropic.com")
        #expect(config.defaultModel == "claude-haiku-4-5-20251001")
    }

    // MARK: - ProviderConfig Init

    @Test("ProviderConfig init with defaults")
    func providerConfigDefaults() {
        let config = ProviderConfig()
        #expect(config.ollama == nil)
        #expect(config.openai == nil)
        #expect(config.anthropic == nil)
    }

    @Test("ProviderConfig init with all providers")
    func providerConfigAllProviders() {
        let config = ProviderConfig(
            ollama: OllamaConfig(),
            openai: OpenAIConfig(apiKey: "sk-test"),
            anthropic: AnthropicConfig(apiKey: "sk-ant-test")
        )
        #expect(config.ollama != nil)
        #expect(config.openai != nil)
        #expect(config.anthropic != nil)
    }

    // MARK: - Encode/Decode

    @Test("OllamaConfig encode/decode roundtrip")
    func ollamaConfigCodable() throws {
        let original = OllamaConfig(baseURL: "http://test:1234", defaultModel: "codellama")
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(OllamaConfig.self, from: data)
        #expect(decoded.baseURL == original.baseURL)
        #expect(decoded.defaultModel == original.defaultModel)
    }

    @Test("OpenAIConfig encode/decode roundtrip")
    func openAIConfigCodable() throws {
        let original = OpenAIConfig(apiKey: "sk-test", baseURL: "https://custom", defaultModel: "gpt-4o")
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(OpenAIConfig.self, from: data)
        #expect(decoded.apiKey == original.apiKey)
        #expect(decoded.baseURL == original.baseURL)
        #expect(decoded.defaultModel == original.defaultModel)
    }

    @Test("AnthropicConfig encode/decode roundtrip")
    func anthropicConfigCodable() throws {
        let original = AnthropicConfig(apiKey: "sk-ant-test", baseURL: "https://custom", defaultModel: "claude-sonnet-4-6")
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(AnthropicConfig.self, from: data)
        #expect(decoded.apiKey == original.apiKey)
        #expect(decoded.baseURL == original.baseURL)
        #expect(decoded.defaultModel == original.defaultModel)
    }

    @Test("ProviderConfig encode/decode roundtrip")
    func providerConfigCodable() throws {
        let original = ProviderConfig(
            ollama: OllamaConfig(),
            openai: OpenAIConfig(apiKey: "sk-test"),
            anthropic: AnthropicConfig(apiKey: "sk-ant")
        )
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(ProviderConfig.self, from: data)
        #expect(decoded.ollama?.baseURL == original.ollama?.baseURL)
        #expect(decoded.openai?.apiKey == original.openai?.apiKey)
        #expect(decoded.anthropic?.apiKey == original.anthropic?.apiKey)
    }

    // MARK: - fromEnvironment

    @Test("fromEnvironment with no env vars and no defaults returns Ollama default only")
    func fromEnvironmentNoEnvNoDefaults() {
        // Note: fromEnvironment always creates Ollama config, but only creates OpenAI/Anthropic if keys exist
        let config = ProviderConfig.fromEnvironment(defaults: nil)
        #expect(config.ollama != nil)
        #expect(config.ollama?.baseURL == "http://localhost:11434")
        #expect(config.ollama?.defaultModel == "llama3.1")
        // Without env vars or defaults for API keys, these should be nil
    }

    @Test("fromEnvironment with defaults for OpenAI")
    func fromEnvironmentWithOpenAIDefaults() {
        let defaults = ProviderConfig(
            openai: OpenAIConfig(apiKey: "default-key", defaultModel: "gpt-3.5-turbo")
        )
        let config = ProviderConfig.fromEnvironment(defaults: defaults)
        #expect(config.openai?.apiKey == "default-key")
        #expect(config.openai?.defaultModel == "gpt-3.5-turbo")
    }

    @Test("fromEnvironment with defaults for Anthropic")
    func fromEnvironmentWithAnthropicDefaults() {
        let defaults = ProviderConfig(
            anthropic: AnthropicConfig(apiKey: "default-ant-key", defaultModel: "claude-haiku-4-5-20251001")
        )
        let config = ProviderConfig.fromEnvironment(defaults: defaults)
        #expect(config.anthropic?.apiKey == "default-ant-key")
        #expect(config.anthropic?.defaultModel == "claude-haiku-4-5-20251001")
    }

    @Test("fromEnvironment with Ollama defaults")
    func fromEnvironmentWithOllamaDefaults() {
        let defaults = ProviderConfig(
            ollama: OllamaConfig(baseURL: "http://custom:9999", defaultModel: "mistral")
        )
        let config = ProviderConfig.fromEnvironment(defaults: defaults)
        #expect(config.ollama?.baseURL == "http://custom:9999")
        #expect(config.ollama?.defaultModel == "mistral")
    }

    @Test("fromEnvironment without API key defaults returns nil for hosted providers")
    func fromEnvironmentNoHostedKeys() {
        let defaults = ProviderConfig(ollama: OllamaConfig())
        let config = ProviderConfig.fromEnvironment(defaults: defaults)
        // No env vars or defaults for OpenAI/Anthropic API keys
        #expect(config.openai == nil)
        #expect(config.anthropic == nil)
    }
}
