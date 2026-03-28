import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// OpenAI hosted AI provider.
public struct OpenAIProvider: AIProvider, Sendable {
    public let id = "openai"
    public let displayName = "OpenAI"
    public let kind: ProviderKind = .hosted
    public let capabilities: [AICapability] = [.analyzeSpec, .generateVariants, .refineProject]

    private let config: OpenAIConfig

    public init(config: OpenAIConfig) {
        self.config = config
    }

    private var apiBaseURL: String {
        config.baseURL ?? "https://api.openai.com/v1"
    }

    public func checkAvailability() async -> Bool {
        guard let url = URL(string: "\(apiBaseURL)/models") else { return false }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    public func validateConfig() async -> ValidateConfigResponse {
        var issues: [String] = []

        if config.apiKey.isEmpty {
            issues.append("OpenAI API key is not configured.")
            return ValidateConfigResponse(valid: false, issues: issues)
        }

        let reachable = await checkAvailability()
        if !reachable {
            issues.append("Could not reach OpenAI API. Check your API key and network.")
        }

        return ValidateConfigResponse(valid: issues.isEmpty, issues: issues)
    }

    public func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult {
        let effectiveModel = model ?? config.defaultModel
        guard let url = URL(string: "\(apiBaseURL)/chat/completions") else {
            throw CompanionAbortError(status: .internalServerError, code: .providerUnavailable, message: "Invalid OpenAI base URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")

        var body: [String: Any] = [
            "model": effectiveModel,
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
            throw CompanionAbortError(status: .badGateway, code: .providerUnavailable, message: "Invalid response from OpenAI")
        }

        if httpResponse.statusCode == 401 {
            throw CompanionAbortError(status: .badGateway, code: .providerAuthInvalid, message: "OpenAI API key is invalid.")
        }

        guard httpResponse.statusCode == 200 else {
            throw CompanionAbortError(
                status: .badGateway,
                code: .providerUnavailable,
                message: "OpenAI returned status \(httpResponse.statusCode)",
                retryable: httpResponse.statusCode == 429 || httpResponse.statusCode >= 500
            )
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let content = message["content"] as? String else {
            throw CompanionAbortError(status: .badGateway, code: .internalError, message: "Unexpected OpenAI response format")
        }

        let usage: UsageInfo?
        if let usageJSON = json["usage"] as? [String: Any] {
            usage = UsageInfo(
                promptTokens: usageJSON["prompt_tokens"] as? Int,
                completionTokens: usageJSON["completion_tokens"] as? Int,
                totalTokens: usageJSON["total_tokens"] as? Int
            )
        } else {
            usage = nil
        }

        return AICompletionResult(text: content, model: effectiveModel, usage: usage)
    }
}
