import Foundation
import Testing
@testable import MoqCompanionAI

@Suite("AI Provider Tests")
struct AIProviderTests {
    // MARK: - OllamaProvider

    @Test("OllamaProvider has correct properties")
    func ollamaProperties() {
        let provider = OllamaProvider(config: OllamaConfig())
        #expect(provider.id == "ollama")
        #expect(provider.displayName == "Ollama")
        #expect(provider.kind == .local)
        #expect(provider.capabilities.count == 3)
        #expect(provider.capabilities.contains(.analyzeSpec))
        #expect(provider.capabilities.contains(.generateVariants))
        #expect(provider.capabilities.contains(.refineProject))
    }

    @Test("OllamaProvider validateConfig with empty-string base URL")
    func ollamaInvalidBaseURL() async {
        // Empty string is not a valid URL
        let config = OllamaConfig(baseURL: "")
        let provider = OllamaProvider(config: config)
        let result = await provider.validateConfig()
        #expect(!result.valid)
    }

    @Test("OllamaProvider checkAvailability returns false for unreachable server")
    func ollamaUnavailable() async {
        let config = OllamaConfig(baseURL: "http://127.0.0.1:19999")
        let provider = OllamaProvider(config: config)
        let available = await provider.checkAvailability()
        #expect(!available)
    }

    @Test("OllamaProvider validateConfig with unreachable server")
    func ollamaUnreachable() async {
        let config = OllamaConfig(baseURL: "http://127.0.0.1:19999")
        let provider = OllamaProvider(config: config)
        let result = await provider.validateConfig()
        #expect(!result.valid)
        #expect(result.issues.first?.contains("not reachable") == true)
    }

    @Test("OllamaProvider checkAvailability returns false for empty URL")
    func ollamaInvalidURL() async {
        let config = OllamaConfig(baseURL: "")
        let provider = OllamaProvider(config: config)
        let available = await provider.checkAvailability()
        #expect(!available)
    }

    @Test("OllamaProvider complete throws for empty base URL")
    func ollamaCompleteInvalidURL() async throws {
        let config = OllamaConfig(baseURL: "")
        let provider = OllamaProvider(config: config)
        // Either CompanionAbortError (guard) or URLError (network layer)
        do {
            _ = try await provider.complete(prompt: "test", model: nil, temperature: nil)
            Issue.record("Expected error")
        } catch {
            // Any error is acceptable — the call should not succeed
            #expect(true)
        }
    }

    // MARK: - OpenAIProvider

    @Test("OpenAIProvider has correct properties")
    func openaiProperties() {
        let provider = OpenAIProvider(config: OpenAIConfig(apiKey: "sk-test"))
        #expect(provider.id == "openai")
        #expect(provider.displayName == "OpenAI")
        #expect(provider.kind == .hosted)
        #expect(provider.capabilities.count == 3)
    }

    @Test("OpenAIProvider validateConfig with empty API key")
    func openaiEmptyKey() async {
        let config = OpenAIConfig(apiKey: "")
        let provider = OpenAIProvider(config: config)
        let result = await provider.validateConfig()
        #expect(!result.valid)
        #expect(result.issues.first?.contains("not configured") == true)
    }

    @Test("OpenAIProvider checkAvailability returns false for invalid key")
    func openaiUnavailable() async {
        let config = OpenAIConfig(apiKey: "invalid-key")
        let provider = OpenAIProvider(config: config)
        let available = await provider.checkAvailability()
        #expect(!available)
    }

    @Test("OpenAIProvider complete throws for empty base URL")
    func openaiCompleteInvalidURL() async throws {
        let config = OpenAIConfig(apiKey: "sk-test", baseURL: "")
        let provider = OpenAIProvider(config: config)
        do {
            _ = try await provider.complete(prompt: "test", model: nil, temperature: nil)
            Issue.record("Expected error")
        } catch {
            #expect(true)
        }
    }

    // MARK: - AnthropicProvider

    @Test("AnthropicProvider has correct properties")
    func anthropicProperties() {
        let provider = AnthropicProvider(config: AnthropicConfig(apiKey: "sk-ant-test"))
        #expect(provider.id == "anthropic")
        #expect(provider.displayName == "Anthropic")
        #expect(provider.kind == .hosted)
        #expect(provider.capabilities.count == 3)
    }

    @Test("AnthropicProvider checkAvailability with valid key returns true")
    func anthropicAvailableWithKey() async {
        let config = AnthropicConfig(apiKey: "sk-ant-valid-long-key")
        let provider = AnthropicProvider(config: config)
        let available = await provider.checkAvailability()
        #expect(available)
    }

    @Test("AnthropicProvider checkAvailability with empty key returns false")
    func anthropicUnavailableEmptyKey() async {
        let config = AnthropicConfig(apiKey: "")
        let provider = AnthropicProvider(config: config)
        let available = await provider.checkAvailability()
        #expect(!available)
    }

    @Test("AnthropicProvider validateConfig with empty API key")
    func anthropicEmptyKey() async {
        let config = AnthropicConfig(apiKey: "")
        let provider = AnthropicProvider(config: config)
        let result = await provider.validateConfig()
        #expect(!result.valid)
        #expect(result.issues.first?.contains("not configured") == true)
    }

    @Test("AnthropicProvider validateConfig with malformed short key")
    func anthropicShortKey() async {
        let config = AnthropicConfig(apiKey: "short")
        let provider = AnthropicProvider(config: config)
        let result = await provider.validateConfig()
        #expect(!result.valid)
        #expect(result.issues.first?.contains("malformed") == true)
    }

    @Test("AnthropicProvider validateConfig with valid key")
    func anthropicValidKey() async {
        let config = AnthropicConfig(apiKey: "sk-ant-api03-this-is-a-valid-key")
        let provider = AnthropicProvider(config: config)
        let result = await provider.validateConfig()
        #expect(result.valid)
        #expect(result.issues.isEmpty)
    }

    @Test("AnthropicProvider complete throws for empty base URL")
    func anthropicCompleteInvalidURL() async throws {
        let config = AnthropicConfig(apiKey: "sk-ant-key", baseURL: "")
        let provider = AnthropicProvider(config: config)
        do {
            _ = try await provider.complete(prompt: "test", model: nil, temperature: nil)
            Issue.record("Expected error")
        } catch {
            #expect(true)
        }
    }
}
