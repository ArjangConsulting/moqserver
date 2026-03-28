import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Anthropic hosted AI provider.
public struct AnthropicProvider: AIProvider, Sendable {
    public let id = "anthropic"
    public let displayName = "Anthropic"
    public let kind: ProviderKind = .hosted
    public let capabilities: [AICapability] = [.analyzeSpec, .generateVariants, .refineProject]

    private let config: AnthropicConfig

    public init(config: AnthropicConfig) {
        self.config = config
    }

    private var apiBaseURL: String {
        config.baseURL ?? "https://api.anthropic.com"
    }

    public func checkAvailability() async -> Bool {
        // Anthropic doesn't have a lightweight list-models endpoint,
        // so we check that the key is non-empty as a basic validation.
        !config.apiKey.isEmpty
    }

    public func validateConfig() async -> ValidateConfigResponse {
        var issues: [String] = []

        if config.apiKey.isEmpty {
            issues.append("Anthropic API key is not configured.")
            return ValidateConfigResponse(valid: false, issues: issues)
        }

        if config.apiKey.count < 10 {
            issues.append("Anthropic API key appears to be malformed.")
        }

        return ValidateConfigResponse(valid: issues.isEmpty, issues: issues)
    }

    public func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult {
        let effectiveModel = model ?? config.defaultModel
        guard let url = URL(string: "\(apiBaseURL)/v1/messages") else {
            throw CompanionAbortError(status: .internalServerError, code: .providerUnavailable, message: "Invalid Anthropic base URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(config.apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")

        var body: [String: Any] = [
            "model": effectiveModel,
            "max_tokens": 4096,
            "messages": [
                ["role": "user", "content": prompt],
            ],
        ]
        if let temperature {
            body["temperature"] = temperature
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw CompanionAbortError(status: .badGateway, code: .providerUnavailable, message: "Invalid response from Anthropic")
        }

        if httpResponse.statusCode == 401 {
            throw CompanionAbortError(status: .badGateway, code: .providerAuthInvalid, message: "Anthropic API key is invalid.")
        }

        guard httpResponse.statusCode == 200 else {
            throw CompanionAbortError(
                status: .badGateway,
                code: .providerUnavailable,
                message: "Anthropic returned status \(httpResponse.statusCode)",
                retryable: httpResponse.statusCode == 429 || httpResponse.statusCode >= 500
            )
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let contentArray = json["content"] as? [[String: Any]],
              let textBlock = contentArray.first(where: { ($0["type"] as? String) == "text" }),
              let text = textBlock["text"] as? String else {
            throw CompanionAbortError(status: .badGateway, code: .internalError, message: "Unexpected Anthropic response format")
        }

        let usage: UsageInfo?
        if let usageJSON = json["usage"] as? [String: Any] {
            usage = UsageInfo(
                promptTokens: usageJSON["input_tokens"] as? Int,
                completionTokens: usageJSON["output_tokens"] as? Int
            )
        } else {
            usage = nil
        }

        return AICompletionResult(text: text, model: effectiveModel, usage: usage)
    }
}
