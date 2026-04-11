package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.*
import com.moqserver.studio.ui.*

private object CriteriaTabStrings {
    const val OVERRIDE_AUTH = "Override Auth Evaluation"
    const val OVERRIDE_AUTH_TOOLTIP = "When enabled, overrides the global auth settings for this specific endpoint."
    const val TYPE_LABEL = "Type"
    const val HEADER_NAME_LABEL = "Header Name"
    const val AUTH_NONE = "None"
    const val AUTH_BEARER = "Bearer Token"
    const val AUTH_BASIC = "Basic Auth"
    const val AUTH_API_KEY = "API Key Header"
    const val AUTH_HEADER = "Custom Header"
    const val DEFAULT_API_KEY_HEADER = "X-API-Key"
    const val DEFAULT_CUSTOM_HEADER = "X-Custom-Header"
    const val NETWORK_SIMULATION = "Network Simulation"
    const val NETWORK_SIMULATION_TOOLTIP = "Simulates network conditions such as latency and packet loss for this endpoint."
    const val LATENCY_LABEL = "Latency (ms)"
    const val LATENCY_TOOLTIP = "Fixed delay in milliseconds added to every response."
    const val JITTER_LABEL = "Jitter (ms)"
    const val JITTER_TOOLTIP = "Random variation in milliseconds added to the latency to simulate an unstable connection."
    const val PACKET_LOSS_LABEL = "Packet Loss %"
    const val PACKET_LOSS_TOOLTIP = "Percentage of requests that will be dropped to simulate packet loss (0-100)."
    const val QUERY_PARAMETERS = "Query Parameters"
    const val PARAMETER_NAME = "Parameter Name"
    const val NO_QUERY_PARAMS = "No query parameters configured."
    const val DELETE_QUERY_PARAM = "Delete query parameter"
    const val VARIANT_MATCH_QUERY = "Variant Match Query"
    const val VARIANT_MATCH_HEADERS = "Variant Match Headers"
    const val HEADER_NAME = "Header Name"
    const val NO_VARIANT_MATCH_QUERY = "No query criteria configured for this variant."
    const val NO_VARIANT_MATCH_HEADERS = "No header criteria configured for this variant."
    const val DELETE_VARIANT_QUERY = "Delete variant query criteria"
    const val DELETE_VARIANT_HEADER = "Delete variant header criteria"
    const val BODY_CONTAINS = "Body Contains"
    const val BODY_CONTAINS_TOOLTIP = "Optional request body substring that must be present before this variant is selected."
}

@Composable
internal fun CriteriaTab(
	variant: ProjectVariant,
	requestRules: RequestRules,
	auth: ProjectAuthConfig?,
	network: NetworkBehavior?,
	onUpdateVariant: (ProjectVariant) -> Unit,
	onUpdate: (RequestRules) -> Unit,
	onUpdateAuth: (ProjectAuthConfig?) -> Unit,
	onUpdateNetwork: (NetworkBehavior?) -> Unit,
) {
	val variantRequestMatch = variant.requestMatch ?: VariantRequestMatch()
	val variantQueryRules = variantRequestMatch.query.orEmpty().entries
		.sortedBy { it.key }
		.map { RuleMatcher(name = it.key, match = it.value, matchType = MatchType.EQUAL_TO) }
	val variantHeaderRules = variantRequestMatch.headers.orEmpty().entries
		.sortedBy { it.key }
		.map { RuleMatcher(name = it.key, match = it.value, matchType = MatchType.EQUAL_TO) }

	fun updateVariantQueryRules(rules: List<RuleMatcher>) {
		val query = rules
			.filter { it.name.isNotBlank() }
			.associate { it.name to (it.match ?: "") }
		onUpdateVariant(variant.withNormalizedRequestMatch(variantRequestMatch.copy(query = query.ifEmpty { null })))
	}

	fun updateVariantHeaderRules(rules: List<RuleMatcher>) {
		val headers = rules
			.filter { it.name.isNotBlank() }
			.associate { it.name to (it.match ?: "") }
		onUpdateVariant(variant.withNormalizedRequestMatch(variantRequestMatch.copy(headers = headers.ifEmpty { null })))
	}

	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xl)) {
		AuthConfigSection(auth = auth, onUpdate = onUpdateAuth)
		NetworkSection(network = network, onUpdate = onUpdateNetwork)
		HorizontalDivider()
		QueryParamsEditor(
			items = requestRules.queryParams ?: emptyList(),
			onUpdate = { queryParams ->
				onUpdate(requestRules.copy(queryParams = queryParams.ifEmpty { null }))
			},
		)
		HorizontalDivider()
		RuleMatcherTableEditor(
			title = CriteriaTabStrings.VARIANT_MATCH_QUERY,
			nameColumnLabel = CriteriaTabStrings.PARAMETER_NAME,
			emptyText = CriteriaTabStrings.NO_VARIANT_MATCH_QUERY,
			items = variantQueryRules,
			onUpdate = ::updateVariantQueryRules,
			onAdd = {
				updateVariantQueryRules(variantQueryRules + RuleMatcher(name = "", matchType = MatchType.EQUAL_TO))
			},
			onClear = { updateVariantQueryRules(emptyList()) },
			deleteContentDescription = CriteriaTabStrings.DELETE_VARIANT_QUERY,
			availableMatchTypes = listOf(MatchType.EQUAL_TO),
			fallbackMatchType = MatchType.EQUAL_TO,
		)
		RuleMatcherTableEditor(
			title = CriteriaTabStrings.VARIANT_MATCH_HEADERS,
			nameColumnLabel = CriteriaTabStrings.HEADER_NAME,
			emptyText = CriteriaTabStrings.NO_VARIANT_MATCH_HEADERS,
			items = variantHeaderRules,
			onUpdate = ::updateVariantHeaderRules,
			onAdd = {
				updateVariantHeaderRules(variantHeaderRules + RuleMatcher(name = "", matchType = MatchType.EQUAL_TO))
			},
			onClear = { updateVariantHeaderRules(emptyList()) },
			deleteContentDescription = CriteriaTabStrings.DELETE_VARIANT_HEADER,
			availableMatchTypes = listOf(MatchType.EQUAL_TO),
			fallbackMatchType = MatchType.EQUAL_TO,
		)
		OutlinedTextField(
			value = variantRequestMatch.bodyContains.orEmpty(),
			onValueChange = {
				onUpdateVariant(variant.withNormalizedRequestMatch(variantRequestMatch.copy(bodyContains = it.ifBlank { null })))
			},
			label = {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(CriteriaTabStrings.BODY_CONTAINS)
					InfoTooltip(CriteriaTabStrings.BODY_CONTAINS_TOOLTIP)
				}
			},
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun AuthConfigSection(
	auth: ProjectAuthConfig?,
	onUpdate: (ProjectAuthConfig?) -> Unit,
) {
	var hasAuth by remember(auth) { mutableStateOf(auth != null) }

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
	) {
		Text(CriteriaTabStrings.OVERRIDE_AUTH, style = MaterialTheme.typography.titleSmall)
		InfoTooltip(CriteriaTabStrings.OVERRIDE_AUTH_TOOLTIP)
		Switch(
			checked = hasAuth,
			onCheckedChange = {
				hasAuth = it
				if (it) {
					onUpdate(ProjectAuthConfig(type = AuthType.BEARER, verify = true))
				} else {
					onUpdate(null)
				}
			},
		)
	}

	if (hasAuth && auth != null) {
		Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
			var typeExpanded by remember { mutableStateOf(false) }
			Box {
				OutlinedTextField(
					value = auth.type.displayLabel(),
					onValueChange = {},
					label = { Text(CriteriaTabStrings.TYPE_LABEL) },
					readOnly = true,
					modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
				)
				Box(modifier = Modifier.matchParentSize().clickable { typeExpanded = true })
				DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
					AuthType.entries.forEach { type ->
						DropdownMenuItem(
							text = { Text(type.displayLabel()) },
							onClick = {
								onUpdate(auth.copy(type = type, headerName = type.defaultHeaderName()))
								typeExpanded = false
							},
						)
					}
				}
			}

			if (auth.type == AuthType.API_KEY || auth.type == AuthType.HEADER) {
				OutlinedTextField(
					value = auth.headerName ?: auth.type.defaultHeaderName() ?: "",
					onValueChange = { onUpdate(auth.copy(headerName = it.ifBlank { null })) },
					label = { Text(CriteriaTabStrings.HEADER_NAME_LABEL) },
					singleLine = true,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

private fun AuthType.displayLabel(): String = when (this) {
	AuthType.NONE -> CriteriaTabStrings.AUTH_NONE
	AuthType.BEARER -> CriteriaTabStrings.AUTH_BEARER
	AuthType.BASIC -> CriteriaTabStrings.AUTH_BASIC
	AuthType.API_KEY -> CriteriaTabStrings.AUTH_API_KEY
	AuthType.HEADER -> CriteriaTabStrings.AUTH_HEADER
}

private fun AuthType.defaultHeaderName(): String? = when (this) {
	AuthType.API_KEY -> CriteriaTabStrings.DEFAULT_API_KEY_HEADER
	AuthType.HEADER -> CriteriaTabStrings.DEFAULT_CUSTOM_HEADER
	else -> null
}

@Composable
private fun NetworkSection(
	network: NetworkBehavior?,
	onUpdate: (NetworkBehavior?) -> Unit,
) {
	var hasNetwork by remember(network) { mutableStateOf(network != null) }

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
	) {
		Text(CriteriaTabStrings.NETWORK_SIMULATION, style = MaterialTheme.typography.titleSmall)
		InfoTooltip(CriteriaTabStrings.NETWORK_SIMULATION_TOOLTIP)
		Switch(
			checked = hasNetwork,
			onCheckedChange = {
				hasNetwork = it
				if (it) {
					onUpdate(NetworkBehavior(latencyMs = 0, jitterMs = 0))
				} else {
					onUpdate(null)
				}
			},
		)
	}

	if (hasNetwork && network != null) {
		Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
            OutlinedTextField(
                value = (network.latencyMs ?: 0).toString(),
                onValueChange = {
                    when (val update = acceptedIntInput(it, network.latencyMs ?: 0)) {
                        is NumericInputUpdate.Accepted -> onUpdate(network.copy(latencyMs = update.value))
                        NumericInputUpdate.Ignored -> Unit
                    }
                },
				label = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(CriteriaTabStrings.LATENCY_LABEL)
						InfoTooltip(CriteriaTabStrings.LATENCY_TOOLTIP)
					}
				},
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
            OutlinedTextField(
                value = (network.jitterMs ?: 0).toString(),
                onValueChange = {
                    when (val update = acceptedIntInput(it, network.jitterMs ?: 0)) {
                        is NumericInputUpdate.Accepted -> onUpdate(network.copy(jitterMs = update.value))
                        NumericInputUpdate.Ignored -> Unit
                    }
                },
				label = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(CriteriaTabStrings.JITTER_LABEL)
						InfoTooltip(CriteriaTabStrings.JITTER_TOOLTIP)
					}
				},
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
            OutlinedTextField(
                value = (network.packetLossPercent ?: 0.0).toString(),
                onValueChange = {
                    when (val update = acceptedDoubleInput(it, network.packetLossPercent ?: 0.0)) {
                        is NumericInputUpdate.Accepted -> onUpdate(network.copy(packetLossPercent = update.value))
                        NumericInputUpdate.Ignored -> Unit
                    }
                },
				label = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(CriteriaTabStrings.PACKET_LOSS_LABEL)
						InfoTooltip(CriteriaTabStrings.PACKET_LOSS_TOOLTIP)
					}
				},
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun QueryParamsEditor(
	items: List<RuleMatcher>,
	onUpdate: (List<RuleMatcher>) -> Unit,
) {
	RuleMatcherTableEditor(
		title = CriteriaTabStrings.QUERY_PARAMETERS,
		nameColumnLabel = CriteriaTabStrings.PARAMETER_NAME,
		emptyText = CriteriaTabStrings.NO_QUERY_PARAMS,
		items = items,
		onUpdate = onUpdate,
		onAdd = {
			onUpdate(items + RuleMatcher(name = "", required = true))
		},
		onClear = {
			onUpdate(emptyList())
		},
		deleteContentDescription = CriteriaTabStrings.DELETE_QUERY_PARAM,
		availableMatchTypes = headerMatchTypes,
		fallbackMatchType = MatchType.EQUAL_TO,
	)
}
