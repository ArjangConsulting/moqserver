package com.moqserver.studio.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Provider Discovery ─────────────────────────────────────────────────────

@Serializable
data class CompanionProvider(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val available: Boolean,
    val capabilities: List<AICapability>,
)

@Serializable
enum class ProviderKind {
    @SerialName("local") LOCAL,
    @SerialName("hosted") HOSTED,
}

@Serializable
enum class AICapability {
    @SerialName("analyze-spec") ANALYZE_SPEC,
    @SerialName("generate-variants") GENERATE_VARIANTS,
    @SerialName("refine-project") REFINE_PROJECT,
}

@Serializable
data class ProvidersResponse(
    val providers: List<CompanionProvider>,
)

// ── Health ──────────────────────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

// ── Validate Config ─────────────────────────────────────────────────────────

@Serializable
data class ValidateConfigRequest(
    val providerId: String,
)

@Serializable
data class ValidateConfigResponse(
    val valid: Boolean,
    val issues: List<String>,
)

// ── Common Request Envelope ─────────────────────────────────────────────────

@Serializable
data class CompanionRequest(
    val providerId: String,
    val projectContext: ProjectContext? = null,
    val selection: SelectionContext? = null,
    val intent: IntentContext? = null,
    val options: RequestOptions? = null,
)

@Serializable
data class ProjectContext(
    val title: String? = null,
    val version: String? = null,
    val endpoints: List<EndpointSummary>? = null,
    val specExcerpt: String? = null,
)

@Serializable
data class EndpointSummary(
    val method: String,
    val path: String,
    val variantCount: Int? = null,
    val hasAuth: Boolean? = null,
    val tags: List<String>? = null,
)

@Serializable
data class SelectionContext(
    val endpointKeys: List<String>? = null,
    val variantNames: List<String>? = null,
)

@Serializable
data class IntentContext(
    val description: String? = null,
    val type: String? = null,
)

@Serializable
data class RequestOptions(
    val model: String? = null,
    val maxVariants: Int? = null,
    val temperature: Double? = null,
)

// ── Common Response Envelope ────────────────────────────────────────────────

@Serializable
data class CompanionResponse<T>(
    val requestId: String,
    val provider: ProviderUsage,
    val result: T,
    val warnings: List<String>? = null,
    val usage: UsageInfo? = null,
)

@Serializable
data class ProviderUsage(
    val id: String,
    val model: String,
)

@Serializable
data class UsageInfo(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val latencyMs: Int? = null,
)

// ── Analyze Spec ────────────────────────────────────────────────────────────

@Serializable
data class AnalyzeSpecResult(
    val findings: List<SpecFinding>,
)

@Serializable
data class SpecFinding(
    val severity: FindingSeverity,
    val category: String,
    val message: String,
    val endpointKey: String? = null,
    val suggestion: String? = null,
)

@Serializable
enum class FindingSeverity {
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

// ── Generate Variants ───────────────────────────────────────────────────────

@Serializable
data class GenerateVariantsResult(
    val variants: List<GeneratedVariant>,
)

@Serializable
data class GeneratedVariant(
    val endpointKey: String,
    val name: String,
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val description: String? = null,
)

// ── Refine Project ──────────────────────────────────────────────────────────

@Serializable
data class RefineProjectResult(
    val suggestions: List<ProjectSuggestion>,
)

@Serializable
data class ProjectSuggestion(
    val category: String,
    val title: String,
    val description: String,
    val affectedEndpoints: List<String>? = null,
)

// ── Error ───────────────────────────────────────────────────────────────────

@Serializable
data class CompanionErrorResponse(
    val error: CompanionErrorDetail,
)

@Serializable
data class CompanionErrorDetail(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, String>? = null,
)

// ── AI State (UI) ───────────────────────────────────────────────────────────

data class AIProviderInfo(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val available: Boolean,
    val capabilities: Set<String>,
    val defaultModel: String? = null,
    val baseUrl: String? = null,
)

data class AIState(
    val providers: List<AIProviderInfo> = emptyList(),
    val selectedProviderId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selectedProvider: AIProviderInfo?
        get() = providers.find { it.id == selectedProviderId }

    val availableProviders: List<AIProviderInfo>
        get() = providers.filter { it.available }

    val hasAvailableProvider: Boolean
        get() = availableProviders.isNotEmpty()

    val isReady: Boolean
        get() = selectedProvider?.available == true
}

data class AIActionState(
    val loading: Boolean = false,
    val action: AIAction? = null,
    val error: String? = null,
    // Results — exactly one will be non-null when loading=false and action!=null
    val analyzeResult: CompanionResponse<AnalyzeSpecResult>? = null,
    val generateResult: CompanionResponse<GenerateVariantsResult>? = null,
    val refineResult: CompanionResponse<RefineProjectResult>? = null,
)

enum class AIAction {
    ANALYZE_SPEC,
    GENERATE_VARIANTS,
    GENERATE_BODY,
    REFINE_PROJECT,
}
