package com.moqserver.studio.projectsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.endpointdetail.AuthFields
import com.moqserver.studio.endpointdetail.NetworkFields
import com.moqserver.studio.endpointdetail.NumericInputUpdate
import com.moqserver.studio.endpointdetail.acceptedBoundedIntInput
import com.moqserver.studio.endpointdetail.requestRuleMatchTypes
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.GlobalRules
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.ui.RuleMatcherTableEditor
import com.moqserver.studio.ui.newRequiredRule
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InfoTooltip
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.ThemeSwitch

private object ProjectSettingsStrings {
	const val GENERAL = "General"
	const val NAME = "Project name"
	const val NAME_REQUIRED = "Project name is required"
	const val DESCRIPTION = "Description (optional)"
	const val DEFAULTS = "Defaults"
	const val DEFAULTS_DESCRIPTION =
		"Applied to every endpoint. An endpoint's own auth or network settings replace these."
	const val DELAY = "Delay (ms)"
	const val DELAY_TOOLTIP = "Added to every response, on top of a variant's own delay."
	const val VERIFY_AUTH = "Check credentials"
	const val VERIFY_AUTH_TOOLTIP =
		"When on, requests without valid credentials of the chosen type get 401. When off, any request is accepted."
	const val VERIFY_AUTH_NEEDS_TYPE = "Choose an auth type to check credentials."
	const val NETWORK = "Network simulation"
	const val GLOBAL_RULES = "Project-wide request rules"
	const val GLOBAL_RULES_DESCRIPTION =
		"Checked on every endpoint. An endpoint header rule with the same name replaces the project one."
	const val REQUIRED_HEADERS = "Required Headers"
	const val HEADER_NAME = "Header Name"
	const val NO_REQUIRED_HEADERS =
		"No project-wide header rules. A rule here also applies to endpoints that must stay unauthenticated."
	const val DELETE_REQUIRED_HEADER = "Delete required header"
	const val VERIFY_COOKIES = "Require at least one cookie"
	const val VERIFY_COOKIES_TOOLTIP =
		"Rejects requests that send no cookies at all, on every endpoint that does not set its own value."
	const val ADDONS = "Add-ons"
	const val ADDONS_DESCRIPTION =
		"Features compiled into moqserver that this bundle turns on, as JSON: add-on id to its config. " +
			"docs/ADDONS.md lists each add-on's keys; validation reports an unknown id or a config the add-on refuses."
	const val ADDONS_LABEL = "Enabled add-ons (JSON)"
}

/**
 * Edits `project.yml`: name and description, defaults, project-wide request rules, add-ons and
 * scenarios. Add-on configs are edited as JSON, since each add-on owns its format.
 */
@Composable
internal fun ProjectSettingsScreen(
	manifest: ProjectManifest,
	endpoints: List<EndpointDocument>,
	onUpdateManifest: (ProjectManifest) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(StudioDimens.xxxl),
		verticalArrangement = Arrangement.spacedBy(StudioDimens.xl),
	) {
		GeneralSection(manifest = manifest, onUpdateManifest = onUpdateManifest)
		DefaultsSection(manifest = manifest, onUpdateManifest = onUpdateManifest)
		GlobalRulesSection(manifest = manifest, onUpdateManifest = onUpdateManifest)
		AddonsSection(manifest = manifest, onUpdateManifest = onUpdateManifest)
		ScenariosSection(
			scenarios = manifest.scenarios.orEmpty(),
			endpoints = endpoints,
			onUpdate = { onUpdateManifest(manifest.withScenarios(it)) },
		)
	}
}

@Composable
private fun GeneralSection(manifest: ProjectManifest, onUpdateManifest: (ProjectManifest) -> Unit) {
	SectionCard(title = ProjectSettingsStrings.GENERAL) {
		FormTextField(
			value = manifest.name,
			onValueChange = { onUpdateManifest(manifest.copy(name = it)) },
			label = ProjectSettingsStrings.NAME,
			errorText = ProjectSettingsStrings.NAME_REQUIRED.takeIf { manifest.name.isBlank() },
			modifier = Modifier.fillMaxWidth(),
		)
		FormTextField(
			value = manifest.description.orEmpty(),
			onValueChange = { onUpdateManifest(manifest.copy(description = it.ifBlank { null })) },
			label = ProjectSettingsStrings.DESCRIPTION,
			singleLine = false,
			modifier = Modifier.fillMaxWidth().heightIn(
				min = StudioDimens.multilineFieldMinHeight,
				max = StudioDimens.multilineFieldMaxHeight,
			),
		)
	}
}

@Composable
private fun DefaultsSection(manifest: ProjectManifest, onUpdateManifest: (ProjectManifest) -> Unit) {
	val defaults = manifest.defaults
	SectionCard(title = ProjectSettingsStrings.DEFAULTS, description = ProjectSettingsStrings.DEFAULTS_DESCRIPTION) {
		FormTextField(
			value = defaults.delayMs.toString(),
			onValueChange = {
				when (val update = acceptedBoundedIntInput(it, defaults.delayMs, minimum = 0)) {
					is NumericInputUpdate.Accepted -> onUpdateManifest(manifest.copy(defaults = defaults.copy(delayMs = update.value)))
					NumericInputUpdate.Ignored -> Unit
				}
			},
			label = ProjectSettingsStrings.DELAY,
			modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
			trailingContent = { InfoTooltip(ProjectSettingsStrings.DELAY_TOOLTIP) },
		)
		AuthFields(
			auth = defaults.auth,
			onUpdate = { onUpdateManifest(manifest.copy(defaults = defaults.copy(auth = it))) },
		)
		// With type `none` the server never checks credentials, whatever `verify` says.
		val hasAuthType = defaults.auth.type != AuthType.NONE
		ThemeSwitch(
			checked = defaults.auth.verify && hasAuthType,
			onCheckedChange = {
				onUpdateManifest(manifest.copy(defaults = defaults.copy(auth = defaults.auth.copy(verify = it))))
			},
			label = ProjectSettingsStrings.VERIFY_AUTH,
			tooltip = ProjectSettingsStrings.VERIFY_AUTH_TOOLTIP,
			enabled = hasAuthType,
			supportingText = ProjectSettingsStrings.VERIFY_AUTH_NEEDS_TYPE.takeUnless { hasAuthType },
		)
		FieldGroupLabel(ProjectSettingsStrings.NETWORK)
		NetworkFields(
			network = defaults.network,
			onUpdate = { onUpdateManifest(manifest.copy(defaults = defaults.copy(network = it))) },
		)
	}
}

@Composable
private fun GlobalRulesSection(manifest: ProjectManifest, onUpdateManifest: (ProjectManifest) -> Unit) {
	val globalRules = manifest.globalRules ?: GlobalRules()
	val requiredHeaders = globalRules.requiredHeaders.orEmpty()
	SectionCard(
		title = ProjectSettingsStrings.GLOBAL_RULES,
		description = ProjectSettingsStrings.GLOBAL_RULES_DESCRIPTION,
	) {
		ThemeSwitch(
			checked = globalRules.verifyCookies == true,
			onCheckedChange = {
				onUpdateManifest(manifest.withGlobalRules(globalRules.copy(verifyCookies = if (it) true else null)))
			},
			label = ProjectSettingsStrings.VERIFY_COOKIES,
			tooltip = ProjectSettingsStrings.VERIFY_COOKIES_TOOLTIP,
		)
		RuleMatcherTableEditor(
			title = ProjectSettingsStrings.REQUIRED_HEADERS,
			nameColumnLabel = ProjectSettingsStrings.HEADER_NAME,
			emptyText = ProjectSettingsStrings.NO_REQUIRED_HEADERS,
			items = requiredHeaders,
			onUpdate = { onUpdateManifest(manifest.withRequiredHeaders(it)) },
			onAdd = { onUpdateManifest(manifest.withRequiredHeaders(requiredHeaders + newRequiredRule())) },
			onClear = { onUpdateManifest(manifest.withRequiredHeaders(emptyList())) },
			deleteContentDescription = ProjectSettingsStrings.DELETE_REQUIRED_HEADER,
			availableMatchTypes = requestRuleMatchTypes,
			showRequiredColumn = true,
		)
	}
}

@Composable
private fun AddonsSection(manifest: ProjectManifest, onUpdateManifest: (ProjectManifest) -> Unit) {
	var text by remember { mutableStateOf(addonsJsonText(manifest.addons)) }
	var error by remember { mutableStateOf<String?>(null) }
	SectionCard(title = ProjectSettingsStrings.ADDONS, description = ProjectSettingsStrings.ADDONS_DESCRIPTION) {
		FormTextField(
			value = text,
			onValueChange = { updated ->
				text = updated
				parseAddonsJsonText(updated)
					.onSuccess { addons ->
						error = null
						if (addons != manifest.addons.orEmpty()) onUpdateManifest(manifest.withAddons(addons))
					}
					.onFailure { error = it.message }
			},
			label = ProjectSettingsStrings.ADDONS_LABEL,
			singleLine = false,
			errorText = error,
			modifier = Modifier.fillMaxWidth().heightIn(
				min = StudioDimens.multilineFieldMinHeight,
				max = StudioDimens.multilineFieldMaxHeight,
			),
		)
	}
}

@Composable
internal fun FieldGroupLabel(text: String) {
	Text(text, style = MaterialTheme.typography.labelLarge)
}
