package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.AIState
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.composeapp.generated.resources.GreatVibes_Regular
import com.moqserver.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

private object AppStrings {
    const val APP_TITLE = "moq studio"
    const val WORKSPACE = "Workspace"
    const val OPEN_OR_IMPORT = "Open or import a project"
    const val OPEN_MOQPROJ = "Open .moqproj"
    const val IMPORT_OPENAPI = "Import OpenAPI"
    const val IMPORT_HAR = "Import HAR"
    const val RECENT_PROJECTS = "Recent Projects"
    const val REMOVE_RECENT_PROJECT = "Remove recent project"
    const val AI_COMPANION = "AI Companion"
    const val CLOSE = "Close"
    const val UNSAVED_CHANGES = "Unsaved changes"
    const val NO_UNSAVED_CHANGES = "No unsaved changes"
    const val AI_CHECKING_PROVIDERS = "AI checking providers"
    const val AI_READY = "AI ready"
    const val AI_NOT_CONFIGURED = "AI not configured"
    const val NO_AI_PROVIDER = "No AI provider available"
    const val AI_PROVIDER_PREFIX = "AI: "
    const val VERSION_PREFIX = "Version "
    const val ENDPOINTS_SUFFIX = " endpoints"
    const val AI_CONNECTION_DETAILS = "AI connection details"
    const val AI_CONNECTION_PROVIDER = "Provider"
    const val AI_CONNECTION_MODEL = "Model"
    const val AI_CONNECTION_TYPE = "Type"
    const val AI_CONNECTION_BASE_URL = "Base URL"
    const val AI_CONNECTION_CAPABILITIES = "Capabilities"
    const val AI_CONNECTION_STATUS = "Status"
    const val AI_CONNECTION_READY = "Connected"
    const val AI_TYPE_LOCAL = "Local"
    const val AI_TYPE_HOSTED = "Hosted"
    const val AI_CAPABILITIES_UNKNOWN = "Not reported"
    const val CAP_ANALYZE = "Analyze"
    const val CAP_GENERATE = "Generate"
    const val CAP_REFINE = "Refine"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    appViewModel: StudioRootViewModel,
    themeMode: StudioThemeMode,
    variantReferenceSyncPreference: VariantReferenceSyncPreference? = null,
    onOpenProject: () -> Unit = {},
    onImportOpenAPI: () -> Unit = {},
    onImportHAR: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onOpenRecentProject: (String) -> Unit = {},
    onRemoveRecentProject: (String) -> Unit = {},
    onAIAction: (AIAction) -> Unit = {},
    onGenerateBody: (String, String, String) -> Unit = { _, _, _ -> },
    onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit = { _, _ -> },
) {
    val state by appViewModel.state.collectAsState()

    Scaffold(
        topBar = {
            StudioTopBar(
                state = state,
            )
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isImporting -> ImportReviewScreen(
                    state = state.importState!!,
                    onToggleEndpoint = { index -> appViewModel.toggleImportEndpoint(index) },
                    onSelectAll = { appViewModel.setAllImportEndpoints(true) },
                    onDeselectAll = { appViewModel.setAllImportEndpoints(false) },
                    onUpdateProjectName = { appViewModel.updateImportProjectName(it) },
                    onConfirm = onConfirmImport,
                    onCancel = { appViewModel.cancelImport() },
                    modifier = Modifier.fillMaxSize(),
                )

                state.project == null -> StudioLandingScreen(
                    state = state,
                    onOpenProject = onOpenProject,
                    onImportOpenAPI = onImportOpenAPI,
                    onImportHAR = onImportHAR,
                    onOpenRecentProject = onOpenRecentProject,
                    onRemoveRecentProject = onRemoveRecentProject,
                )

                else -> StudioWorkspaceScreen(
                    state = state,
                    variantReferenceSyncPreference = variantReferenceSyncPreference,
                    onAIAction = onAIAction,
                    onGenerateBody = onGenerateBody,
                    onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
                    viewModel = appViewModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioTopBar(
    state: StudioState,
) {
    val calligraphyFont = FontFamily(Font(Res.font.GreatVibes_Regular))

    Column {
        TopAppBar(
            title = {
                Text(
                    text = AppStrings.APP_TITLE,
                    fontFamily = calligraphyFont,
                    style = MaterialTheme.typography.headlineMedium,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(),
        )

        val project = state.project
        if (project != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StudioDimens.xl, vertical = StudioDimens.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = project.manifest.name,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
internal fun StudioLandingScreen(
    state: StudioState,
    onOpenProject: () -> Unit,
    onImportOpenAPI: () -> Unit,
    onImportHAR: () -> Unit,
    onOpenRecentProject: (String) -> Unit,
    onRemoveRecentProject: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(StudioDimens.xxxl),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.xxl),
    ) {
        Text(AppStrings.WORKSPACE, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = state.statusLine,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(StudioDimens.xxl), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(AppStrings.OPEN_OR_IMPORT, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                    FilledTonalButton(onClick = onOpenProject) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Text(AppStrings.OPEN_MOQPROJ)
                    }
                    FilledTonalButton(onClick = onImportOpenAPI) {
                        Text(AppStrings.IMPORT_OPENAPI)
                    }
                    FilledTonalButton(onClick = onImportHAR) {
                        Text(AppStrings.IMPORT_HAR)
                    }
                }
            }
        }

        RecentProjectsCard(
            recentProjects = state.recentProjects,
            onOpenProject = onOpenRecentProject,
            onRemoveProject = onRemoveRecentProject,
        )
    }
}

@Composable
internal fun StudioWorkspaceScreen(
    state: StudioState,
    variantReferenceSyncPreference: VariantReferenceSyncPreference?,
    onAIAction: (AIAction) -> Unit,
    onGenerateBody: (String, String, String) -> Unit,
    onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit,
    viewModel: StudioRootViewModel,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if ((state.aiAction.action != null || state.aiAction.loading) && state.aiAction.action != AIAction.GENERATE_BODY) {
                    AIResultsPanel(
                        aiAction = state.aiAction,
                        onDismiss = { viewModel.dismissAIAction() },
                        onAcceptVariant = { viewModel.applyGeneratedVariant(it) },
                        onNavigateToEndpoint = { key ->
                            val parts = key.split(" ", limit = 2)
                            if (parts.size == 2) {
                                state.project?.endpoints
                                    ?.find { it.method == parts[0] && it.path == parts[1] }
                                    ?.let { viewModel.selectEndpoint(it.id) }
                            }
                            viewModel.dismissAIAction()
                        },
                    )
                } else {
                    EndpointBrowser(
                        state = state,
                        onSelectEndpoint = { viewModel.selectEndpoint(it) },
                        detailContent = {
                            val endpoint = state.selectedEndpoint
                            if (endpoint != null) {
                                EndpointDetailPane(
                                    endpoint = endpoint,
                                    originalEndpoint = state.originalProject?.endpoints?.find { it.id == endpoint.id },
                                    allEndpoints = state.project?.endpoints.orEmpty(),
                                    onUpdateEndpoint = { viewModel.updateEndpoint(it) },
                                    onDeleteEndpoint = { viewModel.removeEndpoint(endpoint.id) },
                                    projectPath = state.project?.projectPath.orEmpty(),
                                    variantReferenceSyncPreference = variantReferenceSyncPreference,
                                    aiAvailable = state.ai.isReady,
                                    aiProvider = state.ai.selectedProvider,
                                    aiBodyGenerating = state.aiAction.loading && state.aiAction.action == AIAction.GENERATE_BODY,
                                    aiBodyError = state.aiAction.takeIf { it.action == AIAction.GENERATE_BODY }?.error,
                                    onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
                                    onGenerateBody = { variant, prompt ->
							onGenerateBody(endpoint.id, variant.referenceName, prompt)
						},
                                    initialVariantName = state.pendingVariantName,
                                )
                            }
                        },
                    )
                }
            }

            val diagnostics = state.transientDiagnostic?.let { listOf(it) + state.diagnostics } ?: state.diagnostics
            if (diagnostics.isNotEmpty()) {
                ValidationPanel(
                    diagnostics = diagnostics,
                    transientDiagnostic = state.transientDiagnostic,
                    onDiagnosticClick = { diagnostic ->
                        diagnostic.endpointId?.let { viewModel.selectEndpoint(it, diagnostic.variantName) }
                    },
                    onDismissTransientDiagnostic = { viewModel.dismissError() },
                )
            }

            WorkspaceStatusBar(
                state = state,
            )
        }
    }
}

@Composable
internal fun RecentProjectsCard(
    recentProjects: List<String>,
    onOpenProject: (String) -> Unit,
    onRemoveProject: (String) -> Unit,
) {
    if (recentProjects.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(StudioDimens.xxl), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppStrings.RECENT_PROJECTS, style = MaterialTheme.typography.titleMedium)
            recentProjects.forEach { path ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenProject(path) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(vertical = StudioDimens.xs),
                        verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs),
                    ) {
                        Text(
                            text = recentProjectLabel(path),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onRemoveProject(path) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = AppStrings.REMOVE_RECENT_PROJECT,
                        )
                    }
                }
            }
        }
    }
}

internal fun recentProjectLabel(path: String): String = path.substringAfterLast("/")

@Composable
internal fun WorkspaceStatusBar(
    state: StudioState,
) {
    val project = state.project ?: return
    val dirtyText = if (state.isDirty) AppStrings.UNSAVED_CHANGES else AppStrings.NO_UNSAVED_CHANGES
    val dirtyColor = if (state.isDirty) MaterialTheme.colorScheme.error else StudioColors.success
    val aiStatus = aiStatusPresentation(state.ai)
    val selectedProvider = state.ai.selectedProvider
    val providerLabel = selectedProvider?.displayName ?: aiStatus.label
    val canShowAIDetails = state.ai.isReady && selectedProvider != null
    var showAIDetails by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioDimens.xl, vertical = StudioDimens.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${AppStrings.AI_PROVIDER_PREFIX}$providerLabel", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(StudioDimens.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${AppStrings.VERSION_PREFIX}${project.manifest.version}",
                    style = MaterialTheme.typography.labelMedium,
                )
                StatusSeparator()
                Text(
                    "${project.endpoints.size}${AppStrings.ENDPOINTS_SUFFIX}",
                    style = MaterialTheme.typography.labelMedium,
                )
                StatusSeparator()
                Text(
                    dirtyText,
                    style = MaterialTheme.typography.labelMedium,
                    color = dirtyColor,
                )
                StatusSeparator()
                Box {
                    Text(
                        aiStatus.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = aiStatus.color,
                        modifier = if (canShowAIDetails) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { showAIDetails = true }
                        } else {
                            Modifier
                        },
                    )

                    if (showAIDetails && selectedProvider != null) {
                        AIConnectionDetailsPopover(
                            provider = selectedProvider,
                            onDismiss = { showAIDetails = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AIConnectionDetailsPopover(
    provider: com.moqserver.studio.domain.AIProviderInfo,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = StudioDimens.s)
                .width(320.dp),
        ) {
            Column(
                modifier = Modifier.padding(StudioDimens.l),
                verticalArrangement = Arrangement.spacedBy(StudioDimens.l),
            ) {
                Text(
                    text = AppStrings.AI_CONNECTION_DETAILS,
                    style = MaterialTheme.typography.titleSmall,
                )
                AIConnectionDetailRow(AppStrings.AI_CONNECTION_PROVIDER, provider.displayName)
                provider.defaultModel?.let {
                    AIConnectionDetailRow(AppStrings.AI_CONNECTION_MODEL, it)
                }
                AIConnectionDetailRow(AppStrings.AI_CONNECTION_TYPE, providerKindLabel(provider.kind))
                provider.baseUrl?.let {
                    AIConnectionDetailRow(AppStrings.AI_CONNECTION_BASE_URL, it)
                }
                AIConnectionDetailRow(
                    AppStrings.AI_CONNECTION_CAPABILITIES,
                    providerCapabilitiesLabel(provider),
                )
                AIConnectionDetailRow(
                    AppStrings.AI_CONNECTION_STATUS,
                    AppStrings.AI_CONNECTION_READY,
                    valueColor = StudioColors.success,
                )
            }
        }
    }
}

@Composable
private fun AIConnectionDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

private data class StatusPresentation(
    val label: String,
    val color: Color,
)

private fun providerKindLabel(kind: com.moqserver.studio.domain.ProviderKind): String = when (kind) {
    com.moqserver.studio.domain.ProviderKind.LOCAL -> AppStrings.AI_TYPE_LOCAL
    com.moqserver.studio.domain.ProviderKind.HOSTED -> AppStrings.AI_TYPE_HOSTED
}

private fun providerCapabilitiesLabel(provider: com.moqserver.studio.domain.AIProviderInfo): String {
    if (provider.capabilities.isEmpty()) return AppStrings.AI_CAPABILITIES_UNKNOWN
    return provider.capabilities
        .sorted()
        .joinToString(", ") { capability ->
            when (capability) {
                "ANALYZE_SPEC" -> AppStrings.CAP_ANALYZE
                "GENERATE_VARIANTS" -> AppStrings.CAP_GENERATE
                "REFINE_PROJECT" -> AppStrings.CAP_REFINE
                else -> capability.lowercase().replace("_", " ")
            }
        }
}

@Composable
private fun aiStatusPresentation(ai: AIState): StatusPresentation = when {
    ai.loading -> StatusPresentation(AppStrings.AI_CHECKING_PROVIDERS, MaterialTheme.colorScheme.tertiary)
    ai.isReady -> StatusPresentation(AppStrings.AI_READY, StudioColors.success)
    ai.providers.isEmpty() && ai.error == null -> StatusPresentation(
        AppStrings.AI_NOT_CONFIGURED,
        MaterialTheme.colorScheme.outline,
    )
    else -> StatusPresentation(AppStrings.NO_AI_PROVIDER, MaterialTheme.colorScheme.error)
}

@Composable
private fun StatusSeparator() {
    Box(
        modifier = Modifier
            .height(StudioDimens.l)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
