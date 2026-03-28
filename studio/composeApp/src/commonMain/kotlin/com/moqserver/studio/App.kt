package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.composeapp.generated.resources.GreatVibes_Regular
import com.moqserver.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

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
    onRefreshCompanion: () -> Unit = {},
    onAIAction: (AIAction) -> Unit = {},
) {
    val state by appViewModel.state.collectAsState()
    val showAiPanel = state.aiPanelVisible

    Scaffold(
        topBar = {
            StudioTopBar(
                state = state,
                themeMode = themeMode,
                onThemeModeToggle = {
                    onThemeModeChange(
                        when (themeMode) {
                            StudioThemeMode.SYSTEM,
                            StudioThemeMode.LIGHT -> StudioThemeMode.DARK
                            StudioThemeMode.DARK -> StudioThemeMode.LIGHT
                        }
                    )
                },
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
                )

                else -> StudioWorkspaceScreen(
                    state = state,
                    onCloseProject = onCloseProject,
                    onSaveProject = onSaveProject,
                    onSaveProjectAs = onSaveProjectAs,
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

    Column {
        TopAppBar(
            title = {
                Text(
                    text = "moq studio",
                    fontFamily = calligraphyFont,
                    style = MaterialTheme.typography.headlineMedium,
                )
            },
            actions = {
                IconButton(onClick = onThemeModeToggle) {
                    Icon(
                        imageVector = if (themeMode == StudioThemeMode.DARK) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = "Toggle theme",
                    )
                }
                IconButton(onClick = onToggleAiPanel, enabled = state.project != null) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = if (showAiPanel) "Close AI panel" else "Open AI panel",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(),
        )

        val project = state.project
        if (project != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = project.manifest.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onRefreshCompanion) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Connect or refresh AI companion")
                    }
                    OutlinedButton(onClick = onCloseProject) {
                        Text("Clear Project")
                    }
                    OutlinedButton(onClick = { onSaveProjectAs(project) }) {
                        Text("Save As")
                    }
                    Button(
                        onClick = { onSaveProject(project) },
                        enabled = state.isDirty && !state.hasErrors,
                    ) {
                        Text("Save")
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
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Workspace", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = state.statusLine,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Open or import a project", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenProject) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Text("Open .moqproj")
                    }
                    FilledTonalButton(onClick = onImportOpenAPI) {
                        Text("Import OpenAPI")
                    }
                    FilledTonalButton(onClick = onImportHAR) {
                        Text("Import HAR")
                    }
                }
            }
        }

        RecentProjectsCard(state.recentProjects)
    }
}

@Composable
internal fun StudioWorkspaceScreen(
    state: StudioState,
    onCloseProject: () -> Unit,
    onSaveProject: (MoqProject) -> Unit,
    onSaveProjectAs: (MoqProject) -> Unit,
    onRefreshCompanion: () -> Unit,
    onAIAction: (AIAction) -> Unit,
    showAiPanel: Boolean,
    onCloseAiPanel: () -> Unit,
    viewModel: StudioRootViewModel,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ProjectOverviewCard(
                state = state,
                onCloseProject = onCloseProject,
                onSaveProject = onSaveProject,
                onSaveProjectAs = onSaveProjectAs,
            )

            CompanionStatusBar(
                companion = state.companion,
                onRefresh = onRefreshCompanion,
                onSelectProvider = { viewModel.selectProvider(it) },
            )

            HorizontalDivider()

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
                                    onUpdateEndpoint = { viewModel.updateEndpoint(it) },
                                    companionConnected = state.companion.connected && state.companion.hasAvailableProvider,
                                    onGenerateVariants = { onAIAction(AIAction.GENERATE_VARIANTS) },
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
                        diagnostic.endpointId?.let { viewModel.selectEndpoint(it) }
                    },
                )
            }
        }

        if (showAiPanel) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(380.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("AI Companion", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onCloseAiPanel) {
                            Text("Close")
                        }
                    }
                    ProviderSettingsPanel(
                        companion = state.companion,
                        onRefresh = onRefreshCompanion,
                        onSelectProvider = { viewModel.selectProvider(it) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProjectOverviewCard(
    state: StudioState,
    onCloseProject: () -> Unit,
    onSaveProject: (MoqProject) -> Unit,
    onSaveProjectAs: (MoqProject) -> Unit,
) {
    if (state.project == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        ProjectInfoCard(state)
    }
}

@Composable
internal fun RecentProjectsCard(recentProjects: List<String>) {
    if (recentProjects.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recent Projects", style = MaterialTheme.typography.titleMedium)
            recentProjects.forEach { path ->
                Text(
                    text = path.substringAfterLast("/"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun ProjectInfoCard(state: StudioState) {
    val project = state.project ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Version: ${project.manifest.version}", style = MaterialTheme.typography.labelMedium)
                Text("Endpoints: ${project.endpoints.size}", style = MaterialTheme.typography.labelMedium)
            }
            if (state.isDirty) {
                Text(
                    "Unsaved changes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
