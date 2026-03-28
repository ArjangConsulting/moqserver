import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Ollama local AI provider.
public struct OllamaProvider: AIProvider, Sendable {
    public let id = "ollama"
    public let displayName = "Ollama"
    public let kind: ProviderKind = .local
    public let capabilities: [AICapability] = [.analyzeSpec, .generateVariants, .refineProject]

    private let config: OllamaConfig

    public init(config: OllamaConfig) {
        self.config = config
    }

    public func checkAvailability() async -> Bool {
        guard let url = URL(string: "\(config.baseURL)/api/tags") else { return false }
        do {
            let (_, response) = try await URLSession.shared.data(from: url)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    public func validateConfig() async -> ValidateConfigResponse {
        var issues: [String] = []

        guard URL(string: config.baseURL) != nil else {
            return ValidateConfigResponse(valid: false, issues: ["Invalid base URL: \(config.baseURL)"])
        }

        let reachable = await checkAvailability()
        if !reachable {
            issues.append("Ollama is not reachable at \(config.baseURL). Is it running?")
        }

        return ValidateConfigResponse(valid: issues.isEmpty, issues: issues)
    }

    public func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult {
        let effectiveModel = model ?? config.defaultModel
        guard let url = URL(string: "\(config.baseURL)/api/generate") else {
            throw CompanionAbortError(
                status: .internalServerError,
                code: .providerUnavailable,
                message: "Invalid Ollama base URL"
            )
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        var body: [String: Any] = [
            "model": effectiveModel,
            "prompt": prompt,
            "stream": false,
        ]
        if let temperature {
            body["options"] = ["temperature": temperature]
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw CompanionAbortError(status: .badGateway, code: .providerUnavailable, message: "Invalid response from Ollama")
        }
        guard httpResponse.statusCode == 200 else {
            throw CompanionAbortError(
                status: .badGateway,
                code: .providerUnavailable,
                message: "Ollama returned status \(httpResponse.statusCode)",
                retryable: httpResponse.statusCode >= 500
            )
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let responseText = json["response"] as? String else {
            throw CompanionAbortError(status: .badGateway, code: .internalError, message: "Unexpected Ollama response format")
        }

        return AICompletionResult(text: responseText, model: effectiveModel)
    }
}
