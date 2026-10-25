package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.domain.RuntimeInspectionState
import io.github.maniramezan.kmpcomponents.ContentRow
import io.github.maniramezan.kmpcomponents.EmptyState
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.NoticeCard
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SecretTextField
import io.github.maniramezan.kmpcomponents.StatusTone

private object RuntimeStrings {
	const val TITLE = "Runtime Inspector"
	const val URL = "Server URL"
	const val TOKEN = "Admin bearer token (optional)"
	const val REFRESH = "Refresh"
	const val RESET = "Reset overrides and counters"
	const val NEW_SESSION = "New isolated session"
	const val RELEASE = "Release session"
	const val SESSION = "Send X-Mock-Session with this ID from your test app: "
	const val GLOBAL = "Global server state"
	const val NAME = "Scenario name"
	const val OVERRIDES = "Endpoint variants (JSON: method and template path → variant)"
	const val SAVE = "Save scenario"
	const val ACTIVATE = "Activate "
	const val EDIT = "Edit"
	const val DELETE = "Delete"
	const val CLEAR = "Clear history"
	const val LOADING = "Refreshing runtime state..."
	const val SHOW = "Show"
	const val HIDE = "Hide"
	const val RETRY = "Retry"
	const val EMPTY = "No requests recorded. Make a request to the server, then refresh."
}

@Composable
internal fun RuntimeInspectionWindow(
	state: RuntimeInspectionState,
	onUpdate: (RuntimeInspectionState) -> Unit,
	onAction: (RuntimeAction, String?) -> Unit,
	onClose: () -> Unit,
	themeMode: StudioThemeMode = StudioThemeMode.SYSTEM,
) {
	Window(
		onCloseRequest = onClose,
		title = RuntimeStrings.TITLE,
		state = rememberWindowState(width = 900.dp, height = 780.dp),
	) {
		StudioTheme(themeMode = themeMode) {
			Column(
				Modifier.fillMaxSize().padding(StudioDimens.xl),
				verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
			) {
				ConnectionFields(state, onUpdate)
				FlowLayout {
					ActionButton(RuntimeStrings.REFRESH, RuntimeAction.REFRESH, state, onAction)
					ActionButton(RuntimeStrings.RESET, RuntimeAction.RESET, state, onAction)
					if (state.sessionId.isBlank()) {
						ActionButton(RuntimeStrings.NEW_SESSION, RuntimeAction.CREATE_SESSION, state, onAction)
					} else {
						ActionButton(RuntimeStrings.RELEASE, RuntimeAction.RELEASE_SESSION, state, onAction)
					}
				}
				SelectionContainer {
					Text(if (state.sessionId.isBlank()) RuntimeStrings.GLOBAL else RuntimeStrings.SESSION + state.sessionId)
				}
				if (state.loading) InlineProgressIndicator(label = RuntimeStrings.LOADING)
				state.error?.let {
					NoticeCard(
						message = it,
						tone = StatusTone.ERROR,
						actionLabel = RuntimeStrings.RETRY,
						onAction = { onAction(RuntimeAction.REFRESH, null) },
					)
				}
				ScenarioFields(state, onUpdate, onAction)
				ActionButton(RuntimeStrings.CLEAR, RuntimeAction.CLEAR_HISTORY, state, onAction)
				LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
					items(state.scenarios, key = { "scenario-${it.name}" }) { scenario ->
						Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
							SecondaryButton(
								text = RuntimeStrings.ACTIVATE + scenario.name,
								enabled = !state.loading,
								onClick = { onAction(RuntimeAction.ACTIVATE_SCENARIO, scenario.name) },
							)
							SecondaryButton(
								text = RuntimeStrings.EDIT,
								enabled = !state.loading,
								onClick = {
								onUpdate(state.copy(scenarioName = scenario.name, scenarioOverrides = scenario.overridesJson))
							},
							)
							SecondaryButton(
								text = RuntimeStrings.DELETE,
								enabled = !state.loading,
								onClick = { onAction(RuntimeAction.DELETE_SCENARIO, scenario.name) },
							)
						}
					}
					if (state.requests.isEmpty()) item { EmptyState(title = RuntimeStrings.EMPTY) }
					items(state.requests, key = { it.id }) { request ->
						ContentRow(
							title = "${request.status} ${request.method} ${request.path}",
							supportingText = "${request.reason} · call ${request.callNumber ?: "—"} · ${request.variant ?: "—"}",
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ConnectionFields(state: RuntimeInspectionState, onUpdate: (RuntimeInspectionState) -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		FormTextField(
			label = RuntimeStrings.URL,
			enabled = !state.loading,
			singleLine = true,
			modifier = Modifier.weight(1f),
			value = state.serverUrl,
			onValueChange = {
				onUpdate(state.copy(serverUrl = it, sessionId = "", requests = emptyList(), scenarios = emptyList()))
			},
		)
		SecretTextField(
			value = state.bearerToken,
			onValueChange = { onUpdate(state.copy(bearerToken = it)) },
			label = RuntimeStrings.TOKEN,
			enabled = !state.loading,
			showLabel = RuntimeStrings.SHOW,
			hideLabel = RuntimeStrings.HIDE,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun ScenarioFields(
	state: RuntimeInspectionState,
	onUpdate: (RuntimeInspectionState) -> Unit,
	onAction: (RuntimeAction, String?) -> Unit,
) {
	FormTextField(
		label = RuntimeStrings.NAME,
		enabled = !state.loading,
		singleLine = true,
		value = state.scenarioName,
		onValueChange = { onUpdate(state.copy(scenarioName = it)) },
	)
	FormTextField(
		label = RuntimeStrings.OVERRIDES,
		enabled = !state.loading,
		modifier = Modifier.fillMaxWidth().heightIn(
			min = StudioDimens.multilineFieldMinHeight,
			max = StudioDimens.multilineFieldMaxHeight,
		),
		value = state.scenarioOverrides,
		onValueChange = { onUpdate(state.copy(scenarioOverrides = it)) },
		singleLine = false,
	)
	ActionButton(RuntimeStrings.SAVE, RuntimeAction.SAVE_SCENARIO, state, onAction)
}

@Composable
private fun ActionButton(
	label: String,
	action: RuntimeAction,
	state: RuntimeInspectionState,
	onAction: (RuntimeAction, String?) -> Unit,
) {
	PrimaryButton(
		text = label,
		enabled = !state.loading,
		onClick = { onAction(action, null) },
	)
}
