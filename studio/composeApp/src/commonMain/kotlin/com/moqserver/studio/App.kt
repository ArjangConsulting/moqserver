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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.AIState
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.composeapp.generated.resources.GreatVibes_Regular
import com.moqserver.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

private object AppStrings {
    const val APP_TITLE = "moq studio"
    const val TOGGLE_THEME = "Toggle theme"
    const val CLOSE_AI_PANEL = "Close AI panel"
    const val OPEN_AI_PANEL = "Open AI panel"
    const val CONNECT_REFRESH_AI = "Connect or refresh AI companion"
    const val CLEAR_PROJECT = "Clear Project"
    const val SAVE_AS = "Save As"
    const val SAVE = "Save"
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
    const val VERSION_PREFIX = "Version "
    const val ENDPOINTS_SUFFIX = " endpoints"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    appViewModel: StudioRootViewModel,
    themeMode: StudioThemeMode,
    onThemeModeChange: (StudioThemeMode) -> Unit,
    onToggleAiPanel: () -> Unit = {},
    onOpenProject: () -> Unit = {},
    onCloseProject: () -> Unit = {},
    onSaveProject: (MoqProject) -> Unit = {},
    onSaveProjectAs: (MoqProject) -> Unit = {},
    onImportOpenAPI: () -> Unit = {},
    onImportHAR: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onOpenRecentProject: (String) -> Unit = {},
    onRemoveRecentProject: (String) -> Unit = {},
    onRefreshCompanion: () -> Unit = {},
    onAIAction: (AIAction) -> Unit = {},
) {
    val state by appViewModel.state.collectAsState()
    val showAiPanel = state.aiPanelVisible
    val systemInDarkTheme = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            StudioTopBar(
                state = state,
                themeMode = themeMode,
                onThemeModeToggle = {
                    onThemeModeChange(
                        resolveNextThemeMode(
                            themeMode = themeMode,
                            systemInDarkTheme = systemInDarkTheme,
                        )
                    )
                },
                systemInDarkTheme = systemInDarkTheme,
                showAiPanel = showAiPanel,
                onToggleAiPanel = onToggleAiPanel,
                onCloseProject = onCloseProject,
                onSaveProject = onSaveProject,
                onSaveProjectAs = onSaveProjectAs,
                onRefreshCompanion = onRefreshCompanion,
                onAIAction = onAIAction,
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
                    onRefreshCompanion = onRefreshCompanion,
                    onAIAction = onAIAction,
                    showAiPanel = showAiPanel,
                    onCloseAiPanel = { appViewModel.setAiPanelVisible(false) },
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
    themeMode: StudioThemeMode,
    systemInDarkTheme: Boolean,
    onThemeModeToggle: () -> Unit,
    showAiPanel: Boolean,
    onToggleAiPanel: () -> Unit,
    onCloseProject: () -> Unit,
    onSaveProject: (MoqProject) -> Unit,
    onSaveProjectAs: (MoqProject) -> Unit,
    onRefreshCompanion: () -> Unit,
    onAIAction: (AIAction) -> Unit,
) {
    val calligraphyFont = FontFamily(Font(Res.font.GreatVibes_Regular))
    val darkTheme = resolveDarkTheme(themeMode, systemInDarkTheme)

    Column {
        TopAppBar(
            title = {
                Text(
                    text = AppStrings.APP_TITLE,
                    fontFamily = calligraphyFont,
                    style = MaterialTheme.typography.headlineMedium,
                )
            },
            actions = {
                IconButton(onClick = onThemeModeToggle) {
                    Icon(
                        imageVector = if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = AppStrings.TOGGLE_THEME,
                    )
                }
                IconButton(onClick = onToggleAiPanel, enabled = state.project != null) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = if (showAiPanel) AppStrings.CLOSE_AI_PANEL else AppStrings.OPEN_AI_PANEL,
                    )
                }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onRefreshCompanion) {
                        Icon(Icons.Filled.Refresh, contentDescription = AppStrings.CONNECT_REFRESH_AI)
                    }
                    OutlinedButton(onClick = onCloseProject) {
                        Text(AppStrings.CLEAR_PROJECT)
                    }
                    OutlinedButton(onClick = { onSaveProjectAs(project) }) {
                        Text(AppStrings.SAVE_AS)
                    }
                    Button(
                        onClick = { onSaveProject(project) },
                        enabled = state.isDirty && !state.hasErrors,
                    ) {
                        Text(AppStrings.SAVE)
                    }
                }
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
    onRefreshCompanion: () -> Unit,
    onAIAction: (AIAction) -> Unit,
    showAiPanel: Boolean,
    onCloseAiPanel: () -> Unit,
    viewModel: StudioRootViewModel,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.aiAction.action != null || state.aiAction.loading) {
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
                                    companionConnected = state.ai.isReady,
                                    onGenerateVariants = { onAIAction(AIAction.GENERATE_VARIANTS) },
                                    initialVariantName = state.pendingVariantName,
                                )
                            }
                        },
                    )
                }
            }

            if (state.diagnostics.isNotEmpty()) {
                ValidationPanel(
                    diagnostics = state.diagnostics,
                    onDiagnosticClick = { diagnostic ->
                        diagnostic.endpointId?.let { viewModel.selectEndpoint(it, diagnostic.variantName) }
                    },
                )
            }

            WorkspaceStatusBar(state)
        }

        if (showAiPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { onCloseAiPanel() },
            )
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(StudioDimens.xl)
                    .width(380.dp),
            ) {
                Column(modifier = Modifier.padding(StudioDimens.xl), verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(AppStrings.AI_COMPANION, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onCloseAiPanel) {
                            Text(AppStrings.CLOSE)
                        }
                    }
                    ProviderSettingsPanel(
                        ai = state.ai,
                        onRefresh = onRefreshCompanion,
                        onSelectProvider = { viewModel.selectProvider(it) },
                    )
                }
            }
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
internal fun WorkspaceStatusBar(state: StudioState) {
    val project = state.project ?: return
    val dirtyText = if (state.isDirty) AppStrings.UNSAVED_CHANGES else AppStrings.NO_UNSAVED_CHANGES
    val dirtyColor = if (state.isDirty) MaterialTheme.colorScheme.error else StudioColors.success
    val aiStatus = aiStatusPresentation(state.ai)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioDimens.xl, vertical = StudioDimens.m),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                Text(
                    aiStatus.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = aiStatus.color,
                )
            }
        }
    }
}

private data class StatusPresentation(
    val label: String,
    val color: Color,
)

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
