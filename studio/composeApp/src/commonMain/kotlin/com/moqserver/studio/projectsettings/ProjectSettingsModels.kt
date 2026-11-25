package com.moqserver.studio.projectsettings

import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.GlobalRules
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectScenario
import com.moqserver.studio.projectformat.RuleMatcher

/** Longest scenario name the format accepts (`MoqFormatRules.maxScenarioNameLength`). */
internal const val MAX_SCENARIO_NAME_LENGTH = 100

/** Most scenarios a bundle may declare (`MoqFormatRules.maxScenarios`). */
internal const val MAX_SCENARIOS = 100

internal enum class ScenarioNameProblem { BLANK, TOO_LONG, DUPLICATE }

/** Why [name] cannot be the name of the scenario currently called [currentName], or `null`. */
internal fun scenarioNameProblem(
	name: String,
	currentName: String,
	existingNames: Collection<String>,
): ScenarioNameProblem? =
	when {
		name.isBlank() -> ScenarioNameProblem.BLANK
		name.length > MAX_SCENARIO_NAME_LENGTH -> ScenarioNameProblem.TOO_LONG
		name != currentName && name in existingNames -> ScenarioNameProblem.DUPLICATE
		else -> null
	}

/** The manifest with [scenarios], writing no `scenarios:` key when there are none. */
internal fun ProjectManifest.withScenarios(scenarios: Map<String, ProjectScenario>): ProjectManifest =
	copy(scenarios = scenarios.ifEmpty { null })

/** The manifest with [globalRules], writing no `global_rules:` key when it holds nothing. */
internal fun ProjectManifest.withGlobalRules(globalRules: GlobalRules): ProjectManifest {
	val normalized = globalRules.copy(requiredHeaders = globalRules.requiredHeaders?.ifEmpty { null })
	return copy(globalRules = normalized.takeIf { it != GlobalRules() })
}

internal fun ProjectManifest.withRequiredHeaders(rules: List<RuleMatcher>): ProjectManifest =
	withGlobalRules((globalRules ?: GlobalRules()).copy(requiredHeaders = rules))

/** Renames a scenario in place, keeping the order scenarios are listed in. */
internal fun Map<String, ProjectScenario>.renamingScenario(from: String, to: String): Map<String, ProjectScenario> =
	if (from == to || to in this) {
		this
	} else {
		entries.associate { (name, scenario) -> (if (name == from) to else name) to scenario }
	}

/** The first free `scenario-N` name. */
internal fun nextScenarioName(existingNames: Collection<String>): String =
	generateSequence(1) { it + 1 }.map { "scenario-$it" }.first { it !in existingNames }

/** The variant a new selection starts with: the declared default, else the first. */
internal fun EndpointDocument.initialScenarioVariant(): String? =
	(variants.firstOrNull { it.isDefault == true } ?: variants.firstOrNull())?.name

/**
 * A new scenario selecting the first endpoint's starting variant, since the format requires at
 * least one selection. `null` when the project has no endpoint to select.
 */
internal fun newScenario(endpoints: List<EndpointDocument>): ProjectScenario? {
	val endpoint = endpoints.firstOrNull() ?: return null
	val variant = endpoint.initialScenarioVariant() ?: return null
	return ProjectScenario(variants = mapOf(endpoint.id to variant))
}

/**
 * Sets the selection for [endpointId], replacing the one for [replacing] in the same position
 * when the endpoint of an existing row changes.
 */
internal fun ProjectScenario.withSelection(
	endpointId: String,
	variant: String,
	replacing: String? = null,
): ProjectScenario {
	val target = replacing ?: endpointId
	if (target !in variants) return copy(variants = variants + (endpointId to variant))
	val updated = variants.entries
		.filterNot { it.key == endpointId && it.key != target }
		.associate { (id, selected) -> if (id == target) endpointId to variant else id to selected }
	return copy(variants = updated)
}

internal fun ProjectScenario.withoutSelection(endpointId: String): ProjectScenario =
	copy(variants = variants - endpointId)

/** The endpoint a new selection row starts with: the first one the scenario does not select yet. */
internal fun ProjectScenario.nextUnselectedEndpoint(endpoints: List<EndpointDocument>): EndpointDocument? =
	endpoints.firstOrNull { it.id !in variants }
