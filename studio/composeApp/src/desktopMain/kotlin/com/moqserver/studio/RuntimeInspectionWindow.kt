package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.domain.RuntimeInspectionState

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
				Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
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
				state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
				ScenarioFields(state, onUpdate, onAction)
				ActionButton(RuntimeStrings.CLEAR, RuntimeAction.CLEAR_HISTORY, state, onAction)
				LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
					items(state.scenarios, key = { "scenario-${it.name}" }) { scenario ->
						Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
							OutlinedButton(
								enabled = !state.loading,
								onClick = { onAction(RuntimeAction.ACTIVATE_SCENARIO, scenario.name) },
							) {
								Text(RuntimeStrings.ACTIVATE + scenario.name)
							}
							OutlinedButton(enabled = !state.loading, onClick = {
								onUpdate(state.copy(scenarioName = scenario.name, scenarioOverrides = scenario.overridesJson))
							}) { Text(RuntimeStrings.EDIT) }
							OutlinedButton(enabled = !state.loading, onClick = { onAction(RuntimeAction.DELETE_SCENARIO, scenario.name) }) {
								Text(RuntimeStrings.DELETE)
							}
						}
					}
					if (state.requests.isEmpty()) item { Text(RuntimeStrings.EMPTY) }
					items(state.requests, key = { it.id }) { request ->
						Text("${request.status} ${request.method} ${request.path} — ${request.variant ?: "—"}")
						Text("${request.reason} · call ${request.callNumber ?: "—"}", style = MaterialTheme.typography.bodySmall)
					}
				}
			}
		}
	}
}

@Composable
private fun ConnectionFields(state: RuntimeInspectionState, onUpdate: (RuntimeInspectionState) -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		OutlinedTextField(
			state.serverUrl,
			{ onUpdate(state.copy(serverUrl = it, sessionId = "", requests = emptyList(), scenarios = emptyList())) },
			label = { Text(RuntimeStrings.URL) },
			enabled = !state.loading,
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
		OutlinedTextField(
			state.bearerToken,
			{ onUpdate(state.copy(bearerToken = it)) },
			label = { Text(RuntimeStrings.TOKEN) },
			enabled = !state.loading,
			singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
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
	OutlinedTextField(
		state.scenarioName,
		{ onUpdate(state.copy(scenarioName = it)) },
		label = { Text(RuntimeStrings.NAME) },
		enabled = !state.loading,
		singleLine = true,
	)
	OutlinedTextField(
		state.scenarioOverrides,
		{ onUpdate(state.copy(scenarioOverrides = it)) },
		label = { Text(RuntimeStrings.OVERRIDES) },
		enabled = !state.loading,
		modifier = Modifier.fillMaxWidth(),
		maxLines = 3,
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
	Button(enabled = !state.loading, onClick = { onAction(action, null) }) { Text(label) }
}
