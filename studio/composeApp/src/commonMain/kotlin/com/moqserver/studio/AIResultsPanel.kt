package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.AIActionState
import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.CompanionResponse
import com.moqserver.studio.domain.FindingSeverity
import com.moqserver.studio.domain.GenerateVariantsResult
import com.moqserver.studio.domain.GeneratedVariant
import com.moqserver.studio.domain.RefineProjectResult
import com.moqserver.studio.domain.SpecFinding

private object AIResultsPanelStrings {
    const val SPEC_ANALYSIS = "Spec Analysis"
    const val GENERATED_VARIANTS = "Generated Variants"
    const val PROJECT_SUGGESTIONS = "Project Suggestions"
    const val CLOSE = "Close"
    const val WORKING = "Working..."
    const val ERROR = "Error"
    const val NO_FINDINGS = "No findings."
    const val NO_VARIANTS = "No variants generated."
    const val NO_SUGGESTIONS = "No suggestions."
    const val ACCEPT = "Accept"
    const val SEVERITY_ERROR = "E"
    const val SEVERITY_WARNING = "W"
    const val SEVERITY_INFO = "I"
    const val SUGGESTION_PREFIX = "Suggestion: "
    const val PROVIDER_PREFIX = "Provider: "
}

@Composable
fun AIResultsPanel(
    aiAction: AIActionState,
    onDismiss: () -> Unit,
    onAcceptVariant: (GeneratedVariant) -> Unit,
    onNavigateToEndpoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (aiAction.action == null && !aiAction.loading) return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StudioDimens.xxxl),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.l),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (aiAction.action) {
                    AIAction.ANALYZE_SPEC -> AIResultsPanelStrings.SPEC_ANALYSIS
                    AIAction.GENERATE_VARIANTS -> AIResultsPanelStrings.GENERATED_VARIANTS
                    AIAction.REFINE_PROJECT -> AIResultsPanelStrings.PROJECT_SUGGESTIONS
                    null -> ""
                },
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onDismiss) { Text(AIResultsPanelStrings.CLOSE) }
        }

        HorizontalDivider()

        // Loading
        if (aiAction.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioDimens.l),
                modifier = Modifier.padding(vertical = StudioDimens.xxxl),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(StudioDimens.mediumSpinnerSize),
                    strokeWidth = StudioDimens.spinnerStrokeWidth,
                )
                Text(AIResultsPanelStrings.WORKING, style = MaterialTheme.typography.bodyMedium)
            }
            return
        }

        // Error
        val errorMessage = aiAction.error
        if (errorMessage != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(StudioDimens.xl)) {
                    Text(
                        AIResultsPanelStrings.ERROR,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(errorMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
            return
        }

        // Results
        when (aiAction.action) {
            AIAction.ANALYZE_SPEC -> aiAction.analyzeResult?.let { AnalyzeResultView(it) }
            AIAction.GENERATE_VARIANTS -> aiAction.generateResult?.let {
                GenerateResultView(it, onAcceptVariant)
            }
            AIAction.REFINE_PROJECT -> aiAction.refineResult?.let {
                RefineResultView(it, onNavigateToEndpoint)
            }
            null -> {}
        }

        // Usage info
        val usage = aiAction.analyzeResult?.usage
            ?: aiAction.generateResult?.usage
            ?: aiAction.refineResult?.usage
        val provider = aiAction.analyzeResult?.provider
            ?: aiAction.generateResult?.provider
            ?: aiAction.refineResult?.provider

        if (provider != null) {
            Spacer(Modifier.height(StudioDimens.m))
            Text(
                text = "${AIResultsPanelStrings.PROVIDER_PREFIX}${provider.id} (${provider.model})" +
                    (usage?.latencyMs?.let { " - ${it}ms" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalyzeResultView(response: CompanionResponse<AnalyzeSpecResult>) {
    val findings = response.result.findings
    if (findings.isEmpty()) {
        Text(AIResultsPanelStrings.NO_FINDINGS, style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text("${findings.size} finding(s)", style = MaterialTheme.typography.bodySmall)
    findings.forEach { finding -> FindingCard(finding) }
}

@Composable
private fun FindingCard(finding: SpecFinding) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(StudioDimens.l), horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
            Text(
                text = when (finding.severity) {
                    FindingSeverity.ERROR -> AIResultsPanelStrings.SEVERITY_ERROR
                    FindingSeverity.WARNING -> AIResultsPanelStrings.SEVERITY_WARNING
                    FindingSeverity.INFO -> AIResultsPanelStrings.SEVERITY_INFO
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                            .clip(RoundedCornerShape(StudioDimens.xs))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = StudioDimens.s, vertical = StudioDimens.xxs),
            )
            Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
                Text(finding.message, style = MaterialTheme.typography.bodySmall)
                finding.endpointKey?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                finding.suggestion?.let {
                    Text(
                        "${AIResultsPanelStrings.SUGGESTION_PREFIX}$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerateResultView(
    response: CompanionResponse<GenerateVariantsResult>,
    onAcceptVariant: (GeneratedVariant) -> Unit,
) {
    val variants = response.result.variants
    if (variants.isEmpty()) {
        Text(AIResultsPanelStrings.NO_VARIANTS, style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text("${variants.size} variant(s) generated", style = MaterialTheme.typography.bodySmall)
    variants.forEach { variant -> GeneratedVariantCard(variant, onAcceptVariant) }
}

@Composable
private fun GeneratedVariantCard(
    variant: GeneratedVariant,
    onAccept: (GeneratedVariant) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(StudioDimens.l), verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(variant.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${variant.endpointKey} - ${variant.statusCode} ${variant.contentType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { onAccept(variant) }) { Text(AIResultsPanelStrings.ACCEPT) }
            }

            variant.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // Body preview (truncated)
            val preview = if (variant.body.length > 300) variant.body.take(300) + "..." else variant.body
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(StudioDimens.xs))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(StudioDimens.m),
            )
        }
    }
}

@Composable
private fun RefineResultView(
    response: CompanionResponse<RefineProjectResult>,
    onNavigateToEndpoint: (String) -> Unit,
) {
    val suggestions = response.result.suggestions
    if (suggestions.isEmpty()) {
        Text(AIResultsPanelStrings.NO_SUGGESTIONS, style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text("${suggestions.size} suggestion(s)", style = MaterialTheme.typography.bodySmall)
    suggestions.forEach { suggestion ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(StudioDimens.l), verticalArrangement = Arrangement.spacedBy(StudioDimens.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                    Text(
                        text = suggestion.category,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                    .clip(RoundedCornerShape(StudioDimens.xs))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = StudioDimens.s, vertical = StudioDimens.xxs),
                    )
                    Text(suggestion.title, style = MaterialTheme.typography.titleSmall)
                }
                Text(suggestion.description, style = MaterialTheme.typography.bodySmall)
                suggestion.affectedEndpoints?.takeIf { it.isNotEmpty() }?.let { endpoints ->
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
                        endpoints.forEach { key ->
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
