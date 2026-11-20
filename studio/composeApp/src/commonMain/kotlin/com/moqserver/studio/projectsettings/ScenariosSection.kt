package com.moqserver.studio.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.ProjectScenario
import com.moqserver.studio.projectformat.displayAlias
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeTextButton

private object ScenariosStrings {
	const val TITLE = "Scenarios"
	const val DESCRIPTION =
		"A scenario picks a variant for several endpoints at once. Activate one at runtime with " +
			"PUT /_admin/scenario or from the Runtime Inspector."
	const val ADD_SCENARIO = "Add scenario"
	const val NO_SCENARIOS = "No scenarios."
	const val NO_ENDPOINTS = "Add an endpoint before creating a scenario."
	const val NAME = "Scenario name"
	const val NAME_BLANK = "Scenario name is required"
	const val NAME_TOO_LONG = "Use at most $MAX_SCENARIO_NAME_LENGTH characters"
	const val NAME_DUPLICATE = "Another scenario already has this name"
	const val DESCRIPTION_FIELD = "Description (optional)"
	const val DELETE_SCENARIO = "Delete scenario"
	const val ENDPOINT = "Endpoint"
	const val VARIANT = "Variant"
	const val ADD_SELECTION = "Add endpoint"
	const val DELETE_SELECTION = "Remove endpoint"
	const val UNKNOWN_SUFFIX = " (not in this project)"
}

@Composable
internal fun ScenariosSection(
	scenarios: Map<String, ProjectScenario>,
	endpoints: List<EndpointDocument>,
	onUpdate: (Map<String, ProjectScenario>) -> Unit,
) {
	SectionCard(
		title = ScenariosStrings.TITLE,
		description = ScenariosStrings.DESCRIPTION,
		action = {
			ThemeTextButton(
				text = ScenariosStrings.ADD_SCENARIO,
				onClick = {
					newScenario(endpoints)?.let { onUpdate(scenarios + (nextScenarioName(scenarios.keys) to it)) }
				},
				enabled = endpoints.isNotEmpty() && scenarios.size < MAX_SCENARIOS,
			)
		},
	) {
		when {
			endpoints.isEmpty() -> EmptyText(ScenariosStrings.NO_ENDPOINTS)
			scenarios.isEmpty() -> EmptyText(ScenariosStrings.NO_SCENARIOS)
		}
		scenarios.entries.forEachIndexed { index, (name, scenario) ->
			key(index) {
				ScenarioCard(
					name = name,
					scenario = scenario,
					otherNames = scenarios.keys - name,
					endpoints = endpoints,
					onRename = { onUpdate(scenarios.renamingScenario(name, it)) },
					onUpdate = { updated -> onUpdate(scenarios + (name to updated)) },
					onDelete = { onUpdate(scenarios - name) },
				)
			}
		}
	}
}

@Composable
private fun EmptyText(text: String) {
	Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ScenarioCard(
	name: String,
	scenario: ProjectScenario,
	otherNames: Set<String>,
	endpoints: List<EndpointDocument>,
	onRename: (String) -> Unit,
	onUpdate: (ProjectScenario) -> Unit,
	onDelete: () -> Unit,
) {
	// The name is the scenario's key, so a name that would collide or is blank stays local to the
	// field, with an error, until it is valid.
	var nameText by remember(name) { mutableStateOf(name) }
	val nameProblem = scenarioNameProblem(nameText, name, otherNames)
	ThemeCard(modifier = Modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
			Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m), verticalAlignment = Alignment.CenterVertically) {
				FormTextField(
					value = nameText,
					onValueChange = {
						nameText = it
						if (scenarioNameProblem(it, name, otherNames) == null) onRename(it)
					},
					label = ScenariosStrings.NAME,
					errorText = nameProblem?.message(),
					modifier = Modifier.weight(1f),
				)
				ThemeTextButton(text = ScenariosStrings.DELETE_SCENARIO, onClick = onDelete, destructive = true)
			}
			FormTextField(
				value = scenario.description.orEmpty(),
				onValueChange = { onUpdate(scenario.copy(description = it.ifBlank { null })) },
				label = ScenariosStrings.DESCRIPTION_FIELD,
				modifier = Modifier.fillMaxWidth(),
			)
			scenario.variants.forEach { (endpointId, variant) ->
				key(endpointId) {
					SelectionRow(
						endpointId = endpointId,
						variant = variant,
						selectableEndpoints = endpoints.filter { it.id == endpointId || it.id !in scenario.variants },
						allEndpoints = endpoints,
						canRemove = scenario.variants.size > 1,
						onSelectEndpoint = { endpoint ->
							endpoint.initialScenarioVariant()?.let {
								onUpdate(scenario.withSelection(endpoint.id, it, replacing = endpointId))
							}
						},
						onSelectVariant = { onUpdate(scenario.withSelection(endpointId, it)) },
						onRemove = { onUpdate(scenario.withoutSelection(endpointId)) },
					)
				}
			}
			val nextEndpoint = scenario.nextUnselectedEndpoint(endpoints)
			ThemeTextButton(
				text = ScenariosStrings.ADD_SELECTION,
				onClick = {
					nextEndpoint?.let { endpoint ->
						endpoint.initialScenarioVariant()?.let { onUpdate(scenario.withSelection(endpoint.id, it)) }
					}
				},
				enabled = nextEndpoint != null,
			)
		}
	}
}

@Composable
private fun SelectionRow(
	endpointId: String,
	variant: String,
	selectableEndpoints: List<EndpointDocument>,
	allEndpoints: List<EndpointDocument>,
	canRemove: Boolean,
	onSelectEndpoint: (EndpointDocument) -> Unit,
	onSelectVariant: (String) -> Unit,
	onRemove: () -> Unit,
) {
	val endpoint = allEndpoints.firstOrNull { it.id == endpointId }
	// A scenario may name a variant by reference_name; show the variant it resolves to.
	val selectedVariant = endpoint?.variantNamed(variant)?.name ?: variant
	Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m), verticalAlignment = Alignment.CenterVertically) {
		MenuPicker(
			items = selectableEndpoints.map(EndpointDocument::id),
			selected = endpointId,
			onSelect = { id -> selectableEndpoints.firstOrNull { it.id == id }?.let(onSelectEndpoint) },
			label = ScenariosStrings.ENDPOINT,
			itemLabel = { id -> allEndpoints.selectionLabel(id) },
		)
		MenuPicker(
			items = endpoint?.variants?.map { it.name }.orEmpty(),
			selected = selectedVariant,
			onSelect = onSelectVariant,
			label = ScenariosStrings.VARIANT,
			itemLabel = { it },
			enabled = endpoint != null,
		)
		ThemeTextButton(
			text = ScenariosStrings.DELETE_SELECTION,
			onClick = onRemove,
			enabled = canRemove,
			destructive = true,
		)
	}
}

private fun EndpointDocument.variantNamed(identifier: String) =
	variants.firstOrNull { it.name == identifier || it.referenceName == identifier }

/** The endpoint's alias with its route, or the bare id for an endpoint the project no longer has. */
private fun List<EndpointDocument>.selectionLabel(endpointId: String): String =
	firstOrNull { it.id == endpointId }?.let { "${it.displayAlias} (${it.method} ${it.path})" }
		?: (endpointId + ScenariosStrings.UNKNOWN_SUFFIX)

private fun ScenarioNameProblem.message(): String = when (this) {
	ScenarioNameProblem.BLANK -> ScenariosStrings.NAME_BLANK
	ScenarioNameProblem.TOO_LONG -> ScenariosStrings.NAME_TOO_LONG
	ScenarioNameProblem.DUPLICATE -> ScenariosStrings.NAME_DUPLICATE
}
