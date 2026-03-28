import Foundation
import Vapor

/// Handles all companion API requests.
public struct CompanionHandler: Sendable {
    private static let maxPromptBytes = 100_000

    private let registry: ProviderRegistry
    private let redactionEngine: RedactionEngine

    public init(registry: ProviderRegistry, redactionEngine: RedactionEngine = RedactionEngine()) {
        self.registry = registry
        self.redactionEngine = redactionEngine
    }

    // MARK: - GET /health

    public func health(req: Request) async throws -> HealthResponse {
        HealthResponse()
    }

    // MARK: - GET /ai/providers

    public func providers(req: Request) async throws -> ProvidersResponse {
        let providers = await registry.listProviders()
        return ProvidersResponse(providers: providers)
    }

    // MARK: - POST /ai/validate-config

    public func validateConfig(req: Request) async throws -> ValidateConfigResponse {
        let body = try req.content.decode(ValidateConfigRequest.self)
        guard let provider = await registry.provider(for: body.providerId) else {
            throw CompanionAbortError(
                status: .badRequest,
                code: .providerUnavailable,
                message: "Unknown provider: \(body.providerId)"
            )
        }
        return await provider.validateConfig()
    }

    // MARK: - POST /ai/analyze-spec

    public func analyzeSpec(req: Request) async throws -> CompanionResponse<AnalyzeSpecResult> {
        let body = try req.content.decode(CompanionRequest.self)
        let (provider, _) = try await resolveProvider(body)

        let prompt = buildAnalyzePrompt(body)
        let redacted = redactIfNeeded(prompt, provider: provider)
        try enforceRequestPolicy(prompt, provider: provider)

        let start = Date()
        let completion = try await provider.complete(prompt: redacted.text, model: body.options?.model, temperature: body.options?.temperature)
        let latencyMs = Int(Date().timeIntervalSince(start) * 1000)

        let findings = parseAnalyzeResponse(completion.text)
        var warnings: [String] = []
        if redacted.redactionCount > 0 {
            warnings.append("Redacted \(redacted.redactionCount) sensitive value(s) before sending to provider.")
        }

        return CompanionResponse(
            requestId: generateRequestId(),
            provider: ProviderUsage(id: provider.id, model: completion.model),
            result: findings,
            warnings: warnings.isEmpty ? nil : warnings,
            usage: mergeUsage(completion.usage, latencyMs: latencyMs)
        )
    }

    // MARK: - POST /ai/generate-variants

    public func generateVariants(req: Request) async throws -> CompanionResponse<GenerateVariantsResult> {
        let body = try req.content.decode(CompanionRequest.self)
        let (provider, _) = try await resolveProvider(body)

        let prompt = buildGenerateVariantsPrompt(body)
        let redacted = redactIfNeeded(prompt, provider: provider)
        try enforceRequestPolicy(prompt, provider: provider)

        let start = Date()
        let completion = try await provider.complete(prompt: redacted.text, model: body.options?.model, temperature: body.options?.temperature)
        let latencyMs = Int(Date().timeIntervalSince(start) * 1000)

        let variants = parseGenerateVariantsResponse(completion.text)
        var warnings: [String] = []
        if redacted.redactionCount > 0 {
            warnings.append("Redacted \(redacted.redactionCount) sensitive value(s) before sending to provider.")
        }

        return CompanionResponse(
            requestId: generateRequestId(),
            provider: ProviderUsage(id: provider.id, model: completion.model),
            result: variants,
            warnings: warnings.isEmpty ? nil : warnings,
            usage: mergeUsage(completion.usage, latencyMs: latencyMs)
        )
    }

    // MARK: - POST /ai/refine-project

    public func refineProject(req: Request) async throws -> CompanionResponse<RefineProjectResult> {
        let body = try req.content.decode(CompanionRequest.self)
        let (provider, _) = try await resolveProvider(body)

        let prompt = buildRefineProjectPrompt(body)
        let redacted = redactIfNeeded(prompt, provider: provider)
        try enforceRequestPolicy(prompt, provider: provider)

        let start = Date()
        let completion = try await provider.complete(prompt: redacted.text, model: body.options?.model, temperature: body.options?.temperature)
        let latencyMs = Int(Date().timeIntervalSince(start) * 1000)

        let suggestions = parseRefineProjectResponse(completion.text)
        var warnings: [String] = []
        if redacted.redactionCount > 0 {
            warnings.append("Redacted \(redacted.redactionCount) sensitive value(s) before sending to provider.")
        }

        return CompanionResponse(
            requestId: generateRequestId(),
            provider: ProviderUsage(id: provider.id, model: completion.model),
            result: suggestions,
            warnings: warnings.isEmpty ? nil : warnings,
            usage: mergeUsage(completion.usage, latencyMs: latencyMs)
        )
    }

    // MARK: - Private Helpers

    private func resolveProvider(_ request: CompanionRequest) async throws -> (any AIProvider, ProviderInfo?) {
        guard let provider = await registry.provider(for: request.providerId) else {
            throw CompanionAbortError(
                status: .badRequest,
                code: .providerUnavailable,
                message: "Unknown provider: \(request.providerId)"
            )
        }
        let available = await provider.checkAvailability()
        guard available else {
            throw CompanionAbortError(
                status: .serviceUnavailable,
                code: .providerUnavailable,
                message: "Provider '\(request.providerId)' is not available.",
                retryable: true
            )
        }
        return (provider, nil)
    }

    private func redactIfNeeded(_ text: String, provider: any AIProvider) -> RedactionResult {
        return redactionEngine.redactIfNeeded(text, providerKind: provider.kind)
    }

    private func enforceRequestPolicy(_ text: String, provider: any AIProvider) throws {
        if text.utf8.count > Self.maxPromptBytes {
            throw CompanionAbortError(
                status: .payloadTooLarge,
                code: .requestTooLarge,
                message: "Request exceeds the maximum allowed prompt size."
            )
        }

        if provider.kind == .hosted && redactionEngine.shouldReject(text) {
            throw CompanionAbortError(
                status: .badRequest,
                code: .requestRejectedRedactionPolicy,
                message: "Request contains too many sensitive values to forward to a hosted provider."
            )
        }
    }

    private func generateRequestId() -> String {
        "req_\(UUID().uuidString.prefix(12).lowercased())"
    }

    private func mergeUsage(_ providerUsage: UsageInfo?, latencyMs: Int) -> UsageInfo {
        UsageInfo(
            promptTokens: providerUsage?.promptTokens,
            completionTokens: providerUsage?.completionTokens,
            totalTokens: providerUsage?.totalTokens,
            latencyMs: latencyMs
        )
    }
}
