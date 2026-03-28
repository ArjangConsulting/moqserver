import Foundation

/// Configuration for all AI providers, loaded from disk or environment.
public struct ProviderConfig: Codable, Sendable {
    public var ollama: OllamaConfig?
    public var openai: OpenAIConfig?
    public var anthropic: AnthropicConfig?

    public init(ollama: OllamaConfig? = nil, openai: OpenAIConfig? = nil, anthropic: AnthropicConfig? = nil) {
        self.ollama = ollama
        self.openai = openai
        self.anthropic = anthropic
    }

    /// Load configuration from environment variables, falling back to provided defaults.
    public static func fromEnvironment(defaults: ProviderConfig? = nil) -> ProviderConfig {
        let env = ProcessInfo.processInfo.environment

        let ollama = OllamaConfig(
            baseURL: env["OLLAMA_BASE_URL"] ?? defaults?.ollama?.baseURL ?? "http://localhost:11434",
            defaultModel: env["OLLAMA_MODEL"] ?? defaults?.ollama?.defaultModel ?? "llama3.1"
        )

        let openai: OpenAIConfig?
        if let apiKey = env["OPENAI_API_KEY"] ?? defaults?.openai?.apiKey {
            openai = OpenAIConfig(
                apiKey: apiKey,
                baseURL: env["OPENAI_BASE_URL"] ?? defaults?.openai?.baseURL,
                defaultModel: env["OPENAI_MODEL"] ?? defaults?.openai?.defaultModel ?? "gpt-4o"
            )
        } else {
            openai = defaults?.openai
        }

        let anthropic: AnthropicConfig?
        if let apiKey = env["ANTHROPIC_API_KEY"] ?? defaults?.anthropic?.apiKey {
            anthropic = AnthropicConfig(
                apiKey: apiKey,
                baseURL: env["ANTHROPIC_BASE_URL"] ?? defaults?.anthropic?.baseURL,
                defaultModel: env["ANTHROPIC_MODEL"] ?? defaults?.anthropic?.defaultModel ?? "claude-sonnet-4-6"
            )
        } else {
            anthropic = defaults?.anthropic
        }

        return ProviderConfig(ollama: ollama, openai: openai, anthropic: anthropic)
    }
}

public struct OllamaConfig: Codable, Sendable {
    public var baseURL: String
    public var defaultModel: String

    public init(baseURL: String = "http://localhost:11434", defaultModel: String = "llama3.1") {
        self.baseURL = baseURL
        self.defaultModel = defaultModel
    }
}

public struct OpenAIConfig: Codable, Sendable {
    public var apiKey: String
    public var baseURL: String?
    public var defaultModel: String

    public init(apiKey: String, baseURL: String? = nil, defaultModel: String = "gpt-4o") {
        self.apiKey = apiKey
        self.baseURL = baseURL
        self.defaultModel = defaultModel
    }
}

public struct AnthropicConfig: Codable, Sendable {
    public var apiKey: String
    public var baseURL: String?
    public var defaultModel: String

    public init(apiKey: String, baseURL: String? = nil, defaultModel: String = "claude-sonnet-4-6") {
        self.apiKey = apiKey
        self.baseURL = baseURL
        self.defaultModel = defaultModel
    }
}
