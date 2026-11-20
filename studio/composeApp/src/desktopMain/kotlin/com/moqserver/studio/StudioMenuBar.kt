package com.moqserver.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.moqserver.studio.domain.StudioState

@Composable
internal fun FrameWindowScope.StudioMenuBar(
	state: StudioState,
	shortcuts: StudioShortcuts,
	commands: StudioMenuCommands,
) {
	val hasProject = state.project != null
	MenuBar {
		Menu("File") {
			Item("Open Project", onClick = commands.project::requestOpenProject)
			Item(
				"Save",
				enabled = hasProject && state.isDirty && !state.hasErrors,
				shortcut = shortcuts.save,
				onClick = commands.project::requestSave,
			)
			Item("Save As", enabled = hasProject, shortcut = shortcuts.saveAs, onClick = commands.project::requestSaveAs)
			Separator()
			Item("Close Project", enabled = hasProject, onClick = commands.project::requestCloseProject)
			if (state.recentProjects.isNotEmpty()) {
				Separator()
				Menu("Recent Projects") {
					state.recentProjects.forEach { path ->
						Item(recentProjectLabel(path), onClick = { commands.project.requestOpenRecentProject(path) })
					}
				}
			}
			Separator()
			Menu("Update", enabled = hasProject) {
				Menu("From File") {
					ImportFileKind.entries.forEach { kind ->
						Item(
							kind.label.substringBefore(' '),
							enabled = hasProject,
							onClick = { commands.import.requestUpdateFromFile(kind) },
						)
					}
				}
				Menu("From URL") {
					URLImportMode.entries.forEach { mode ->
						Item(
							mode.menuLabel,
							enabled = hasProject,
							onClick = { commands.import.requestImportFromURL(mode, URLImportAction.UPDATE) },
						)
					}
				}
			}
			Separator()
			Item(
				"Project Settings...",
				enabled = hasProject,
				onClick = { commands.appState.showProjectSettings = true },
			)
			Item(
				"Generate Client Code...",
				enabled = hasProject,
				onClick = {
					commands.appState.exportState = ExportReferencesState()
					commands.appState.showExport = true
				},
			)
		}
		Menu("Edit") {
			Item("Undo", enabled = state.canUndo, shortcut = shortcuts.undo, onClick = commands.onUndo)
			Item("Redo", enabled = state.canRedo, shortcut = shortcuts.redo, onClick = commands.onRedo)
			if (shortcuts.showsPreferencesInMenuBar) {
				Separator()
				Item("Preferences...", onClick = { commands.appState.showSettings = true })
			}
		}
		Menu("Tools") {
			Item("Runtime Inspector", onClick = { commands.appState.showRuntimeInspector = true })
			Item("Retry format service", onClick = commands.onRetryFormatService)
			Item("Reload Project", enabled = hasProject, onClick = commands.project::requestReloadProject)
		}
		Menu("Help") {
			Item("About $STUDIO_APP_DISPLAY_NAME", onClick = commands.onShowAbout)
		}
	}
}

private val URLImportMode.menuLabel: String
	get() = when (this) {
		URLImportMode.OPENAPI -> "OpenAPI"
		URLImportMode.SWAGGER -> "Swagger"
	}
