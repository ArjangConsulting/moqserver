package com.moqserver.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.moqserver.studio.domain.AIActionState
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.AIState
import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.CompanionResponse
import com.moqserver.studio.domain.FindingSeverity
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.domain.ProviderKind
import com.moqserver.studio.domain.SpecFinding
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.ValidationDiagnostic
import com.moqserver.studio.projectformat.YamlValue

private fun previewProject(): MoqProject {
    return MoqProject(
        manifest = ProjectManifest(
            version = "1",
            name = "Sample API",
            description = "Preview project",
            defaults = ProjectDefaults(
                delayMs = 0,
                auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                network = NetworkBehavior(),
            ),
        ),
        endpoints = listOf(
            EndpointDocument(
                id = "get-users",
                alias = "Users",
                method = "GET",
                path = "/users",
                tags = listOf("public", "preview"),
                requestRules = RequestRules(),
                variants = listOf(
                    ProjectVariant(
                        name = "default",
                        isDefault = true,
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = YamlValue.Obj(
                            mapOf(
                                "users" to YamlValue.Array(
                                    listOf(
                                        YamlValue.Obj(
                                            mapOf(
                                                "id" to YamlValue.Int(1),
                                                "name" to YamlValue.Str("Moq"),
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                    )
                ),
            ),
            EndpointDocument(
                id = "post-login",
                alias = "Login",
                method = "POST",
                path = "/login",
                variants = listOf(
                    ProjectVariant(
                        name = "default",
                        isDefault = true,
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = YamlValue.Obj(mapOf("token" to YamlValue.Str("preview-token"))),
                    )
                ),
            ),
        ),
        projectPath = "/tmp/sample-api",
    )
}

private fun previewState(): StudioState {
    val project = previewProject()
    return StudioState(
        project = project,
        originalProject = project,
        selectedEndpointId = "get-users",
        ai = AIState(
            providers = listOf(
                AIProviderInfo(
                    id = "ollama",
                    displayName = "Ollama (local)",
                    kind = ProviderKind.LOCAL,
                    available = true,
                    capabilities = setOf("ANALYZE_SPEC", "GENERATE_VARIANTS", "REFINE_PROJECT"),
                )
            ),
            selectedProviderId = "ollama",
        ),
        diagnostics = listOf(
            ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.WARNING,
                message = "Example warning for preview",
                endpointId = "get-users",
            )
        ),
    )
}

private fun previewImportState(): ImportState {
    val endpoint = ParsedEndpoint(
        method = "GET",
        path = "/pets",
        responses = listOf(
            ParsedResponse(
                name = "default",
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"pets":[]}""",
            )
        ),
    )
    return ImportState(
        source = ImportSourceType.OPENAPI,
        sourceFileName = "petstore.yaml",
        parsedSpec = ParsedSpec(
            title = "Petstore",
            version = "1.0.0",
            endpoints = listOf(endpoint),
        ),
        entries = listOf(ImportEndpointEntry(endpoint = endpoint, accepted = true)),
        projectName = "Petstore",
    )
}

@Preview
@Composable
private fun LandingPreview() {
    StudioTheme(StudioThemeMode.LIGHT) {
        StudioLandingScreen(
            state = StudioState(recentProjects = listOf("/tmp/sample-api", "/tmp/another-api")),
            onOpenProject = {},
            onImportOpenAPI = {},
            onImportHAR = {},
        )
    }
}

@Preview
@Composable
private fun WorkspacePreview() {
    StudioTheme(StudioThemeMode.DARK) {
        StudioWorkspaceScreen(
            state = previewState(),
            onCloseProject = {},
            onSaveProject = {},
            onSaveProjectAs = {},
            onRefreshCompanion = {},
            onAIAction = {},
            showAiPanel = true,
            onCloseAiPanel = {},
            viewModel = StudioRootViewModel(),
        )
    }
}

@Preview
@Composable
private fun ImportReviewPreview() {
    StudioTheme(StudioThemeMode.LIGHT) {
        ImportReviewScreen(
            state = previewImportState(),
            onToggleEndpoint = {},
            onSelectAll = {},
            onDeselectAll = {},
            onUpdateProjectName = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun AIResultsPreview() {
    val aiState = AIActionState(
        action = com.moqserver.studio.domain.AIAction.ANALYZE_SPEC,
        analyzeResult = CompanionResponse(
            requestId = "preview",
            provider = com.moqserver.studio.domain.ProviderUsage(id = "local", model = "preview"),
            result = AnalyzeSpecResult(
                findings = listOf(
                    SpecFinding(
                        severity = FindingSeverity.WARNING,
                        category = "schema",
                        message = "Example preview finding",
                        endpointKey = "GET /users",
                    )
                )
            ),
        ),
    )

    StudioTheme(StudioThemeMode.DARK) {
        AIResultsPanel(
            aiAction = aiState,
            onDismiss = {},
            onAcceptVariant = {},
            onNavigateToEndpoint = {},
        )
    }
}
