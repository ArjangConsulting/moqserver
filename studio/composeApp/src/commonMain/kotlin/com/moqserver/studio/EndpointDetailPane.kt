package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.moqserver.studio.editor.JsonCodeEditor
import com.moqserver.studio.projectformat.*
import com.moqserver.studio.ui.*
import kotlinx.serialization.json.*
import java.awt.Color as AwtColor

private val headerMatchTypes = listOf(
    MatchType.CONTAINS,
    MatchType.NOT_CONTAINS,
    MatchType.BEGINS_WITH,
    MatchType.ENDS_WITH,
    MatchType.MATCHES_REGEX,
    MatchType.IS_EMPTY,
    MatchType.NOT_EMPTY,
    MatchType.EQUAL_TO,
)

private val headerNameColumnWidth = 160.dp
private val headerConditionColumnWidth = 200.dp
private val headerMatchValueColumnWidth = 180.dp
private val bodyEditorJson = Json { ignoreUnknownKeys = true }
private val bodyEditorPrettyJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
private val bodyPanelColor = Color(0xFF161B22).copy(alpha = 0.97f)
private val bodyPanelBorderColor = Color(0xFF30363D)
private val bodyPanelContentColor = Color(0xFFE6EDF3)
private val bodyEditorBackgroundColor = AwtColor(0x16, 0x1B, 0x22)
private val bodyEditorForegroundColor = AwtColor(0xE6, 0xED, 0xF3)

@Composable
private fun placeholderTextFieldColors() = OutlinedTextFieldDefaults.colors().let { defaults ->
    OutlinedTextFieldDefaults.colors(
        focusedPlaceholderColor = defaults.disabledPlaceholderColor,
        unfocusedPlaceholderColor = defaults.disabledPlaceholderColor,
        disabledPlaceholderColor = defaults.disabledPlaceholderColor,
        errorPlaceholderColor = defaults.disabledPlaceholderColor,
    )
}

private enum class VariantDetailTab(val title: String) {
    SUMMARY("Summary"),
    HEADERS("Headers"),
    COOKIES("Cookies"),
    BODY("Body"),
    CRITERIA("Criteria"),
}

private enum class BodyFormat(val label: String) {
    RAW("Raw"),
    JSON("JSON"),
    PLAIN_TEXT("Plain Text"),
}

private val autoNameRegex = Regex("^(Variant|Success|Error)( \\d+)?$")

/** True when the variant was just created and carries no meaningful user edits. */
private fun ProjectVariant.isPristine(): Boolean =
    name.matches(autoNameRegex) &&
        body == null &&
        bodyFile == null &&
        (headers == null || headers!!.isEmpty()) &&
        (delayMs == null || delayMs == 0) &&
        isDefault != true

internal fun ProjectVariant.hasSessionEdits(originalVariants: List<ProjectVariant>): Boolean {
    val originalVariant = originalVariants.firstOrNull { it.referenceName == referenceName }
        ?: originalVariants.firstOrNull { it.name == name }
    return if (originalVariant != null) this != originalVariant else !isPristine()
}

@Composable
fun EndpointDetailPane(
    endpoint: EndpointDocument,
    originalEndpoint: EndpointDocument? = null,
    allEndpoints: List<EndpointDocument>,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    onDeleteEndpoint: () -> Unit = {},
    projectPath: String = "",
    companionConnected: Boolean = false,
    onGenerateVariants: () -> Unit = {},
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete endpoint?") },
            text = { Text("Delete \"${endpoint.displayAlias}\" (${endpoint.method} ${endpoint.path})? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteEndpoint()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MethodBadge(endpoint.method)
            Text(endpoint.path, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        }

        Text(
            endpoint.displayAlias,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        @OptIn(ExperimentalLayoutApi::class)
        endpoint.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        EndpointMetadataForm(endpoint, allEndpoints, onUpdateEndpoint)
        HorizontalDivider()
        VariantSection(endpoint, originalEndpoint, onUpdateEndpoint, projectPath, companionConnected, onGenerateVariants)
    }
}

@Composable
private fun EndpointMetadataForm(
    endpoint: EndpointDocument,
    allEndpoints: List<EndpointDocument>,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
) {
    Text("Metadata", style = MaterialTheme.typography.titleMedium)
    val siblingReferenceNames = allEndpoints
        .asSequence()
        .filter { it.id != endpoint.id }
        .map(EndpointDocument::referenceName)
        .toSet()
    val referenceNameBlank = endpoint.referenceName.isBlank()
    val referenceNameInvalid = endpoint.referenceName.isNotBlank() && !isValidReferenceName(endpoint.referenceName)
    val referenceNameConflict = endpoint.referenceName.isNotBlank() && endpoint.referenceName in siblingReferenceNames

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var methodExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = endpoint.method,
                onValueChange = {},
                label = { Text("Method") },
                readOnly = true,
                modifier = Modifier.width(120.dp),
            )
            Box(modifier = Modifier.matchParentSize().clickable { methodExpanded = true })
            DropdownMenu(expanded = methodExpanded, onDismissRequest = { methodExpanded = false }) {
                listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").forEach { method ->
                    DropdownMenuItem(
                        text = { Text(method) },
                        onClick = {
                            onUpdateEndpoint(endpoint.copy(method = method))
                            methodExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = endpoint.path,
            onValueChange = { onUpdateEndpoint(endpoint.copy(path = it)) },
            label = { Text("Path") },
            singleLine = true,
            trailingIcon = { InfoTooltip("URL path this endpoint responds to (e.g. /users/{id}).") },
            modifier = Modifier.weight(1f),
        )
    }

    OutlinedTextField(
        value = endpoint.alias ?: endpoint.displayAlias,
        onValueChange = {
            onUpdateEndpoint(endpoint.copy(alias = it.ifBlank { null }))
        },
        label = { Text("Alias (display name)") },
        singleLine = true,
        trailingIcon = { InfoTooltip("Human-readable display name shown in the endpoint browser.") },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = endpoint.referenceName,
        onValueChange = { onUpdateEndpoint(endpoint.copy(referenceName = it)) },
        label = { Text("Reference Name") },
        singleLine = true,
        isError = referenceNameBlank || referenceNameInvalid || referenceNameConflict,
        supportingText = {
            when {
                referenceNameBlank -> Text("Reference name is required")
                referenceNameInvalid -> Text("Use letters, numbers, and underscores only")
                referenceNameConflict -> Text("Reference name must be unique across APIs")
            }
        },
        trailingIcon = { InfoTooltip("Code-friendly unique name used by test cases to reference this API.") },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = endpoint.description.orEmpty(),
        onValueChange = { onUpdateEndpoint(endpoint.copy(description = it.ifBlank { null })) },
        label = { Text("Description (optional)") },
        minLines = 2,
        maxLines = 4,
        trailingIcon = { InfoTooltip("Optional notes that give more context about what this API does.") },
        modifier = Modifier.fillMaxWidth(),
    )

}

@Composable
private fun AuthConfigSection(
    auth: ProjectAuthConfig?,
    onUpdate: (ProjectAuthConfig?) -> Unit,
) {
    var hasAuth by remember(auth) { mutableStateOf(auth != null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Override Auth Evaluation", style = MaterialTheme.typography.titleSmall)
        InfoTooltip("When enabled, overrides the global auth settings for this specific endpoint.")
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var typeExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = auth.type.displayLabel(),
                    onValueChange = {},
                    label = { Text("Type") },
                    readOnly = true,
                    modifier = Modifier.width(160.dp),
                )
                // Transparent overlay — readOnly TextFields still consume touch events,
                // so the clickable must sit on top rather than on the field itself.
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
                    label = { Text("Header Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun AuthType.displayLabel(): String = when (this) {
    AuthType.NONE -> "None"
    AuthType.BEARER -> "Bearer Token"
    AuthType.BASIC -> "Basic Auth"
    AuthType.API_KEY -> "API Key Header"
    AuthType.HEADER -> "Custom Header"
}

private fun AuthType.defaultHeaderName(): String? = when (this) {
    AuthType.API_KEY -> "X-API-Key"
    AuthType.HEADER -> "X-Custom-Header"
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Network Simulation", style = MaterialTheme.typography.titleSmall)
        InfoTooltip("Simulates network conditions such as latency and packet loss for this endpoint.")
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = (network.latencyMs ?: 0).toString(),
                onValueChange = {
                    val ms = it.toIntOrNull() ?: 0
                    onUpdate(network.copy(latencyMs = ms))
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Latency (ms)")
                        InfoTooltip("Fixed delay in milliseconds added to every response.")
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = (network.jitterMs ?: 0).toString(),
                onValueChange = {
                    val ms = it.toIntOrNull() ?: 0
                    onUpdate(network.copy(jitterMs = ms))
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Jitter (ms)")
                        InfoTooltip("Random variation in milliseconds added to the latency to simulate an unstable connection.")
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = (network.packetLossPercent ?: 0.0).toString(),
                onValueChange = {
                    val pct = it.toDoubleOrNull() ?: 0.0
                    onUpdate(network.copy(packetLossPercent = pct))
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Packet Loss %")
                        InfoTooltip("Percentage of requests that will be dropped to simulate packet loss (0-100).")
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VariantSection(
    endpoint: EndpointDocument,
    originalEndpoint: EndpointDocument? = null,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    projectPath: String = "",
    companionConnected: Boolean = false,
    onGenerateVariants: () -> Unit = {},
) {
    var selectedVariantIndex by remember(endpoint.id) { mutableStateOf(0) }
    var selectedTab by remember(endpoint.id) { mutableStateOf(VariantDetailTab.SUMMARY) }
    val activeVariantIndex = selectedVariantIndex.coerceIn(0, endpoint.variants.lastIndex.coerceAtLeast(0))
    val requestRules = endpoint.requestRules ?: RequestRules()
    var variantIndexToDelete by remember { mutableStateOf<Int?>(null) }

    fun removeVariant(index: Int) {
        val updatedVariants = endpoint.variants.toMutableList().also { it.removeAt(index) }
        onUpdateEndpoint(endpoint.copy(variants = updatedVariants))
        selectedVariantIndex = (selectedVariantIndex.coerceAtMost(updatedVariants.lastIndex)).coerceAtLeast(0)
    }

    fun requestRemove(index: Int) {
        val variant = endpoint.variants.getOrNull(index) ?: return
        val hasSessionEdits = variant.hasSessionEdits(originalEndpoint?.variants.orEmpty())
        if (hasSessionEdits) variantIndexToDelete = index else removeVariant(index)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Variants (${endpoint.variants.size})", style = MaterialTheme.typography.titleMedium)
        if (companionConnected) {
            androidx.compose.material3.FilledTonalButton(onClick = onGenerateVariants) {
                Text("AI Generate")
            }
        }
    }

    VariantTabs(
        variants = endpoint.variants,
        selectedIndex = activeVariantIndex,
        onSelect = { selectedVariantIndex = it },
        onAdd = {
            val newStatus = defaultNewVariantStatus(endpoint.variants)
            val newVariant = ProjectVariant(
                name = suggestedVariantName(
                    status = newStatus,
                    existingNames = endpoint.variants.map(ProjectVariant::name),
                    preferredName = "Variant",
                ),
                status = newStatus,
            )
            val updated = endpoint.copy(variants = endpoint.variants + newVariant)
            onUpdateEndpoint(updated)
            selectedVariantIndex = updated.variants.lastIndex
            selectedTab = VariantDetailTab.SUMMARY
        },
        onRemove = { requestRemove(it) },
    )

    variantIndexToDelete?.let { deleteIndex ->
        val variantName = endpoint.variants.getOrNull(deleteIndex)?.name ?: "this variant"
        AlertDialog(
            onDismissRequest = { variantIndexToDelete = null },
            title = { Text("Delete variant?") },
            text = { Text("\"$variantName\" has been edited. Deleting it cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeVariant(deleteIndex)
                        variantIndexToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { variantIndexToDelete = null }) { Text("Cancel") }
            },
        )
    }

    endpoint.variants.getOrNull(activeVariantIndex)?.let { variant ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DetailTabs(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )

                when (selectedTab) {
                    VariantDetailTab.SUMMARY -> VariantSummaryTab(
                        endpoint = endpoint,
                        variant = variant,
                        onUpdate = { updated ->
                            onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                        },
                        onUpdateEndpoint = onUpdateEndpoint,
                        onRemove = { requestRemove(activeVariantIndex) },
                        canRemove = endpoint.variants.size > 1,
                    )

                    VariantDetailTab.HEADERS -> HeadersTab(
                        headers = variant.headers ?: emptyMap(),
                        requestRules = requestRules,
                        onUpdateHeaders = { headers ->
                            onUpdateEndpoint(
                                endpoint.updateVariant(
                                    activeVariantIndex,
                                    variant.copy(headers = headers.ifEmpty { null }),
                                ),
                            )
                        },
                        onUpdateRules = { updatedRules ->
                            onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                        },
                    )

                    VariantDetailTab.COOKIES -> CookiesTab(
                        requestRules = requestRules,
                        onUpdate = { updatedRules ->
                            onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                        },
                    )

                    VariantDetailTab.BODY -> BodyTab(
                        variant = variant,
                        projectPath = projectPath,
                        onUpdate = { updated ->
                            onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                        },
                    )

                    VariantDetailTab.CRITERIA -> CriteriaTab(
                        requestRules = requestRules,
                        auth = endpoint.auth,
                        network = endpoint.network,
                        onUpdate = { updatedRules ->
                            onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                        },
                        onUpdateAuth = { onUpdateEndpoint(endpoint.copy(auth = it)) },
                        onUpdateNetwork = { onUpdateEndpoint(endpoint.copy(network = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantTabs(
    variants: List<ProjectVariant>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    TabStrip {
        variants.forEachIndexed { index, variant ->
            val isSelected = index == selectedIndex
            val label = buildString {
                append(variant.name)
                if (variant.isDefault == true) append(" *")
            }
            TabChip(
                label = label,
                selected = isSelected,
                onClick = { onSelect(index) },
                onClose = if (variants.size > 1) { { onRemove(index) } } else null,
            )
        }
        TabChip(
            label = "+",
            selected = false,
            onClick = onAdd,
        )
    }
}

@Composable
private fun DetailTabs(
    selectedTab: VariantDetailTab,
    onSelect: (VariantDetailTab) -> Unit,
) {
    TabStrip {
        VariantDetailTab.entries.forEach { tab ->
            TabChip(
                label = tab.title,
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun VariantSummaryTab(
    endpoint: EndpointDocument,
    variant: ProjectVariant,
    onUpdate: (ProjectVariant) -> Unit,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // API call summary card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MethodBadge(endpoint.method)
                    Text(
                        endpoint.path,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(variant.status)
                }

                val responseHeaders = variant.headers
                if (!responseHeaders.isNullOrEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val importantKeys = listOf("content-type", "content-length", "location", "cache-control")
                    val displayed = responseHeaders.entries
                        .sortedBy { entry ->
                            val idx = importantKeys.indexOf(entry.key.lowercase())
                            if (idx == -1) importantKeys.size else idx
                        }
                        .take(4)
                    displayed.forEach { (key, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (responseHeaders.size > 4) {
                        Text(
                            "+${responseHeaders.size - 4} more headers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Variant editing controls
        val siblingNames = endpoint.variants.map { it.name }.filter { it != variant.name }.toSet()
        val siblingReferenceNames = endpoint.variants
            .filter { it != variant }
            .map(ProjectVariant::referenceName)
            .toSet()
        val nameConflict = variant.name.isNotBlank() && variant.name in siblingNames
        val referenceNameBlank = variant.referenceName.isBlank()
        val referenceNameInvalid = variant.referenceName.isNotBlank() && !isValidReferenceName(variant.referenceName)
        val referenceNameConflict = variant.referenceName.isNotBlank() && variant.referenceName in siblingReferenceNames
        val statusError = variant.status !in 100..599
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = variant.name,
                onValueChange = { onUpdate(variant.copy(name = it)) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Variant Name")
                        InfoTooltip("Display name for this variant. Must be unique within the endpoint.")
                    }
                },
                singleLine = true,
                isError = nameConflict,
                supportingText = if (nameConflict) {
                    { Text("Name must be unique within this endpoint") }
                } else null,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = variant.referenceName,
                onValueChange = { onUpdate(variant.copy(referenceName = it)) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reference Name")
                        InfoTooltip("Code-friendly name used in tests. Must be unique within this API.")
                    }
                },
                singleLine = true,
                isError = referenceNameBlank || referenceNameInvalid || referenceNameConflict,
                supportingText = {
                    when {
                        referenceNameBlank -> Text("Reference name is required")
                        referenceNameInvalid -> Text("Use letters, numbers, and underscores only")
                        referenceNameConflict -> Text("Reference name must be unique within this API")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = variant.status.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { status ->
                        onUpdate(variant.copy(status = status))
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status")
                        InfoTooltip("HTTP status code this variant returns (100–599).")
                    }
                },
                singleLine = true,
                isError = statusError,
                supportingText = if (statusError) { { Text("100–599") } } else null,
                modifier = Modifier.width(130.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default", style = MaterialTheme.typography.bodySmall)
            InfoTooltip("When enabled, this variant is returned by default when no matching criteria exist.")
            if (canRemove) {
                Switch(
                    checked = variant.isDefault == true,
                    onCheckedChange = { onUpdate(variant.copy(isDefault = if (it) true else null)) },
                )
            } else {
                Text(
                    text = "Only variant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = (variant.delayMs ?: 0).toString(),
                onValueChange = {
                    val ms = it.toIntOrNull()
                    onUpdate(variant.copy(delayMs = if (ms != null && ms > 0) ms else null))
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Delay (ms)")
                        InfoTooltip("Artificial delay in milliseconds before the response is sent.")
                    }
                },
                singleLine = true,
                modifier = Modifier.width(160.dp),
            )
        }

        if (canRemove) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(
                    onClick = onRemove,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove Variant")
                }
            }
        }
    }
}

@Composable
private fun CookiesTab(
    requestRules: RequestRules,
    onUpdate: (RequestRules) -> Unit,
) {
    val cookies = requestRules.cookies ?: emptyList()
    val placeholderColors = placeholderTextFieldColors()

    fun normalizedCookie(cookie: RuleMatcher): RuleMatcher {
        val value = cookie.match?.takeUnless { it.isBlank() }
        val isRequired = cookie.required == true
        val currentMatchType = cookie.matchType
        val currentNeedsValue = currentMatchType != null && currentMatchType !in setOf(
            MatchType.REQUIRE,
            MatchType.IS_EMPTY,
            MatchType.NOT_EMPTY,
        )
        return cookie.copy(
            match = value,
            required = if (isRequired) true else null,
            matchType = when {
                !isRequired -> null
                value != null -> currentMatchType ?: MatchType.EQUAL_TO
                currentNeedsValue -> MatchType.EQUAL_TO
                else -> currentMatchType ?: MatchType.EQUAL_TO
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Request Cookies", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cookies.isNotEmpty()) {
                    TextButton(onClick = {
                        onUpdate(requestRules.copy(cookies = null))
                    }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = {
                    onUpdate(requestRules.copy(cookies = cookies + RuleMatcher(name = "", required = true)))
                }) {
                    Text("Add")
                }
            }
        }

        if (cookies.isEmpty()) {
            Text(
                text = "No cookies configured. Add a cookie to set up cookie-based matching criteria.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Cookie Name",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(headerNameColumnWidth),
            )
            Text(
                "Cookie Value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(2f),
            )
            Text(
                "Required",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(80.dp),
            )
            Text(
                "Condition",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(headerConditionColumnWidth),
            )
            Text(
                "Match Value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(headerMatchValueColumnWidth),
            )
            Spacer(Modifier.width(32.dp))
        }

        HorizontalDivider()

        cookies.forEachIndexed { index, cookie ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = cookie.name,
                    onValueChange = { newName ->
                        onUpdate(requestRules.copy(cookies = cookies.updated(index, normalizedCookie(cookie.copy(name = newName)))))
                    },
                    placeholder = { Text("Cookie name", color = placeholderColors.disabledPlaceholderColor) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = placeholderColors,
                    modifier = Modifier.width(headerNameColumnWidth),
                )
                OutlinedTextField(
                    value = cookie.match.orEmpty(),
                    onValueChange = { newValue ->
                        onUpdate(
                            requestRules.copy(
                                cookies = cookies.updated(
                                    index,
                                    normalizedCookie(cookie.copy(match = newValue)),
                                ),
                            ),
                        )
                    },
                    placeholder = { Text("Value", color = placeholderColors.disabledPlaceholderColor) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = placeholderColors,
                    modifier = Modifier.weight(2f),
                )
                Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                    Checkbox(
                        checked = cookie.required == true,
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                normalizedCookie(cookie.copy(required = true, matchType = cookie.matchType ?: MatchType.EQUAL_TO))
                            } else {
                                cookie.copy(required = null, matchType = null)
                            }
                            onUpdate(requestRules.copy(cookies = cookies.updated(index, updated)))
                        },
                    )
                }
                MatchConditionEditor(
                    ruleMatcher = cookie,
                    onUpdate = { updated ->
                        if (cookie.required != true) return@MatchConditionEditor
                        onUpdate(requestRules.copy(cookies = cookies.updated(index, normalizedCookie(updated.copy(required = true)))))
                    },
                    availableMatchTypes = headerMatchTypes,
                    fallbackMatchType = MatchType.EQUAL_TO,
                    enabled = cookie.required == true,
                    dropdownWidth = headerConditionColumnWidth,
                    valueWidth = headerMatchValueColumnWidth,
                    showValuePlaceholder = true,
                )
                IconButton(
                    onClick = {
                        val updated = cookies.toMutableList().also { it.removeAt(index) }
                        onUpdate(requestRules.copy(cookies = updated.ifEmpty { null }))
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete cookie",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyTab(
    variant: ProjectVariant,
    projectPath: String = "",
    onUpdate: (ProjectVariant) -> Unit,
) {
    val bodyFile = variant.bodyFile
    val body = variant.body

    val fileContent = if (bodyFile != null) {
        remember(projectPath, bodyFile) {
            runCatching {
                java.io.File(projectPath, bodyFile).takeIf { it.isFile }?.readText()
            }.getOrNull()
        }
    } else {
        null
    }
    var selectedFormat by remember(variant.name) { mutableStateOf(BodyFormat.RAW) }
    val currentText = when {
        fileContent != null -> fileContent
        body != null -> when (selectedFormat) {
            BodyFormat.JSON -> yamlValueToJsonString(body)
            BodyFormat.PLAIN_TEXT -> yamlValueToPlainText(body)
            BodyFormat.RAW -> yamlValueToDisplayString(body)
        }
        else -> null
    }
    val editableText = when {
        fileContent != null -> fileContent
        body != null -> editableBodyText(variant, body)
        else -> null
    }
    val isJsonBody = variant.isJsonBody(editableText)
    var isEditing by remember(variant.name, selectedFormat, bodyFile, body) { mutableStateOf(false) }
    var draftText by remember(variant.name, bodyFile, body) { mutableStateOf(editableText.orEmpty()) }
    var formatBeforeEdit by remember(variant.name) { mutableStateOf(BodyFormat.RAW) }
    var validationError by remember(variant.name, selectedFormat, bodyFile, body) { mutableStateOf<String?>(null) }
    val validationErrorRequester = remember { BringIntoViewRequester() }

    fun setValidationError(message: String?) {
        validationError = message
    }

    LaunchedEffect(validationError) {
        if (validationError != null) {
            withFrameNanos { }
            validationErrorRequester.bringIntoView()
        }
    }

    fun startEditing() {
        formatBeforeEdit = selectedFormat
        selectedFormat = BodyFormat.RAW
        draftText = editableText.orEmpty()
        validationError = null
        isEditing = true
    }

    fun cancelEditing() {
        selectedFormat = formatBeforeEdit
        draftText = editableText.orEmpty()
        validationError = null
        isEditing = false
    }

    fun validateJson() {
        setValidationError(validateJsonBodyText(draftText))
    }

    fun formatJson() {
        formatJsonBodyText(draftText)
            .onSuccess { formatted ->
                draftText = formatted
                setValidationError(null)
            }
            .onFailure { error ->
                setValidationError(invalidJsonMessage(error))
            }
    }

    fun saveEdits() {
        when {
            isJsonBody -> {
                parseJsonBodyText(draftText)
                    .onSuccess { parsedBody ->
                        selectedFormat = formatBeforeEdit
                        onUpdate(variant.copy(body = parsedBody, bodyFile = null))
                        setValidationError(null)
                        isEditing = false
                    }
                    .onFailure { error ->
                        setValidationError(invalidJsonMessage(error))
                    }
            }
            else -> {
                selectedFormat = formatBeforeEdit
                val updatedBody = parseEditableText(draftText, body)
                onUpdate(variant.copy(body = updatedBody, bodyFile = null))
                setValidationError(null)
                isEditing = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            bodyFile != null && fileContent == null -> {
                Text(
                    text = "File not found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            currentText != null -> {
                TabStrip {
                    BodyFormat.entries.forEach { format ->
                        TabChip(
                            label = format.label,
                            selected = format == selectedFormat,
                            onClick = { selectedFormat = format },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(bodyPanelColor)
                        .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
                ) {
                    Surface(
                        modifier = Modifier.matchParentSize(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, bodyPanelBorderColor),
                    ) {}
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            BodyTabActions(
                                isEditing = isEditing,
                                canEdit = true,
                                isJsonBody = isJsonBody,
                                onEdit = ::startEditing,
                                onCancel = ::cancelEditing,
                                onSave = ::saveEdits,
                                onValidateJson = ::validateJson,
                                onFormatJson = ::formatJson,
                            )
                        }

                        if (isEditing) {
                            OutlinedTextField(
                                value = draftText,
                                onValueChange = { updated ->
                                    draftText = updated
                                    if (validationError != null && isJsonBody) {
                                        validationError = validateJsonBodyText(updated)
                                    }
                                },
                                readOnly = false,
                                minLines = 8,
                                maxLines = 18,
                                colors = bodyEditorFieldColors(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp, start = 6.dp, end = 4.dp, bottom = 4.dp),
                            )
                        } else when (selectedFormat) {
                            BodyFormat.JSON -> {
                                JsonCodeEditor(
                                    text = currentText,
                                    onTextChange = {},
                                    readOnly = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                        .padding(top = 2.dp, start = 6.dp, end = 4.dp, bottom = 4.dp),
                                    backgroundColor = bodyEditorBackgroundColor,
                                    foregroundColor = bodyEditorForegroundColor,
                                )
                            }
                            BodyFormat.PLAIN_TEXT, BodyFormat.RAW -> {
                                OutlinedTextField(
                                    value = currentText,
                                    onValueChange = {},
                                    readOnly = true,
                                    minLines = 8,
                                    maxLines = 18,
                                    colors = bodyEditorFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, start = 6.dp, end = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }

                validationError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.bringIntoViewRequester(validationErrorRequester),
                    )
                }
            }

            else -> {
                Text(
                    "This variant has no response body yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun bodyEditorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedTextColor = bodyPanelContentColor,
    unfocusedTextColor = bodyPanelContentColor,
    disabledTextColor = bodyPanelContentColor,
    cursorColor = bodyPanelContentColor,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
)

@Composable
private fun BodyTabActions(
    isEditing: Boolean,
    canEdit: Boolean,
    isJsonBody: Boolean,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onValidateJson: () -> Unit,
    onFormatJson: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEditing && isJsonBody) {
            OutlinedButton(onClick = onValidateJson, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Validate JSON")
            }
            FilledTonalButton(onClick = onFormatJson, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Format JSON")
            }
        }

        when {
            isEditing -> {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cancel body edit")
                }
                FilledTonalIconButton(onClick = onSave) {
                    Icon(Icons.Outlined.Save, contentDescription = "Save body edit")
                }
            }
            canEdit -> {
                FilledTonalIconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit body")
                }
            }
        }
    }
}

@Composable
private fun CriteriaTab(
    requestRules: RequestRules,
    auth: ProjectAuthConfig?,
    network: NetworkBehavior?,
    onUpdate: (RequestRules) -> Unit,
    onUpdateAuth: (ProjectAuthConfig?) -> Unit,
    onUpdateNetwork: (NetworkBehavior?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AuthConfigSection(auth = auth, onUpdate = onUpdateAuth)

        NetworkSection(network = network, onUpdate = onUpdateNetwork)

        HorizontalDivider()

        QueryParamsEditor(
            items = requestRules.queryParams ?: emptyList(),
            onUpdate = { queryParams ->
                onUpdate(requestRules.copy(queryParams = queryParams.ifEmpty { null }))
            },
        )
    }
}

@Composable
private fun HeadersTab(
    headers: Map<String, String>,
    requestRules: RequestRules,
    onUpdateHeaders: (Map<String, String>) -> Unit,
    onUpdateRules: (RequestRules) -> Unit,
) {
    val headerCriteria = requestRules.headers ?: emptyList()
    val headersList = headers.entries.toList()
    val placeholderColors = placeholderTextFieldColors()

    fun removeHeader(name: String, wasRequired: Boolean) {
        onUpdateHeaders(headers.filterKeys { !it.equals(name, ignoreCase = true) })
        if (wasRequired) {
            onUpdateRules(
                requestRules.copy(
                    headers = headerCriteria
                        .filter { !it.name.equals(name, ignoreCase = true) }
                        .ifEmpty { null },
                ),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Response Headers", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (headers.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = {
                        onUpdateHeaders(emptyMap())
                        onUpdateRules(requestRules.copy(headers = null))
                    }) {
                        Text("Clear")
                    }
                }
                androidx.compose.material3.TextButton(onClick = {
                    onUpdateHeaders(headers + ("" to ""))
                }) {
                    Text("Add")
                }
            }
        }

        if (headers.isEmpty()) {
            Text(
                "No response headers configured for this variant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Table column headers
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Header Name",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(headerNameColumnWidth),
                )
                Text(
                    "Header Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(2f),
                )
                Text(
                    "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(80.dp),
                )
                Text(
                    "Condition",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(headerConditionColumnWidth),
                )
                Text(
                    "Match Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(headerMatchValueColumnWidth),
                )
                Spacer(Modifier.width(32.dp))
            }

            HorizontalDivider()

            // Table data rows
            headersList.forEachIndexed { index, (key, value) ->
                val ruleMatcher = headerCriteria.find { it.name.equals(key, ignoreCase = true) }
                val isRequired = ruleMatcher != null

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { newKey ->
                            val entries = headers.entries.toMutableList()
                            entries[index] = object : Map.Entry<String, String> {
                                override val key = newKey
                                override val value = value
                            }
                            onUpdateHeaders(entries.associate { it.key to it.value })
                            if (isRequired) {
                                val updatedCriteria = headerCriteria.map {
                                    if (it.name.equals(key, ignoreCase = true)) it.copy(name = newKey) else it
                                }
                                onUpdateRules(requestRules.copy(headers = updatedCriteria))
                            }
                        },
                        placeholder = { Text("Name", color = placeholderColors.disabledPlaceholderColor) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = placeholderColors,
                        modifier = Modifier.width(headerNameColumnWidth),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { newValue ->
                            val updated = headers.entries.mapIndexed { entryIndex, entry ->
                                if (entryIndex == index) newValue else entry.value
                            }
                            onUpdateHeaders(headers.keys.zip(updated).associate { it.first to it.second })
                        },
                        placeholder = { Text("Value", color = placeholderColors.disabledPlaceholderColor) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = placeholderColors,
                        modifier = Modifier.weight(2f),
                    )
                    Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = isRequired,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onUpdateRules(
                                        requestRules.copy(
                                            headers = headerCriteria + RuleMatcher(
                                                name = key,
                                                required = true,
                                                matchType = MatchType.EQUAL_TO,
                                            ),
                                        ),
                                    )
                                } else {
                                    onUpdateRules(
                                        requestRules.copy(
                                            headers = headerCriteria
                                                .filter { !it.name.equals(key, ignoreCase = true) }
                                                .ifEmpty { null },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                    MatchConditionEditor(
                        ruleMatcher = ruleMatcher ?: RuleMatcher(
                            name = key,
                            required = false,
                            matchType = MatchType.EQUAL_TO,
                        ),
                        onUpdate = { updated ->
                            if (!isRequired) return@MatchConditionEditor
                            onUpdateRules(
                                requestRules.copy(
                                    headers = headerCriteria.map {
                                        if (it.name.equals(key, ignoreCase = true)) updated else it
                                    },
                                ),
                            )
                        },
                        availableMatchTypes = headerMatchTypes,
                        fallbackMatchType = MatchType.EQUAL_TO,
                        enabled = isRequired,
                        dropdownWidth = headerConditionColumnWidth,
                        valueWidth = headerMatchValueColumnWidth,
                        showValuePlaceholder = true,
                    )
                    IconButton(
                        onClick = { removeHeader(key, isRequired) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete header",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryParamsEditor(
    items: List<RuleMatcher>,
    onUpdate: (List<RuleMatcher>) -> Unit,
) {
    RuleMatcherTableEditor(
        title = "Query Parameters",
        nameColumnLabel = "Parameter Name",
        emptyText = "No query parameters configured.",
        items = items,
        onUpdate = onUpdate,
        onAdd = {
            onUpdate(items + RuleMatcher(name = "", required = true))
        },
        onClear = {
            onUpdate(emptyList())
        },
        deleteContentDescription = "Delete query parameter",
        availableMatchTypes = headerMatchTypes,
        fallbackMatchType = MatchType.EQUAL_TO,
    )
}

private fun EndpointDocument.updateVariant(index: Int, variant: ProjectVariant): EndpointDocument {
    val updatedVariants = variants.toMutableList()
    updatedVariants[index] = variant
    return copy(variants = updatedVariants)
}

private fun RequestRules.normalize(): RequestRules? {
    return takeIf {
        !it.headers.isNullOrEmpty() ||
            !it.queryParams.isNullOrEmpty() ||
            !it.cookies.isNullOrEmpty()
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> {
    return toMutableList().also { it[index] = value }
}

private fun defaultNewVariantStatus(variants: List<ProjectVariant>): Int {
    return when {
        variants.none { it.status in 200..299 } -> 200
        variants.none { it.status in 400..599 } -> 500
        else -> if (variants.last().status in 400..599) 200 else 500
    }
}

private fun yamlValueToDisplayString(value: YamlValue, indent: Int = 0): String {
    val pad = " ".repeat(indent)
    return when (value) {
        is YamlValue.Null -> "null"
        is YamlValue.Bool -> if (value.value) "true" else "false"
        is YamlValue.Int -> "${value.value}"
        is YamlValue.Double -> "${value.value}"
        is YamlValue.Str -> "\"${value.value}\""
        is YamlValue.Array -> {
            if (value.value.isEmpty()) "[]"
            else "[${value.value.joinToString(", ") { yamlValueToDisplayString(it) }}]"
        }
        is YamlValue.Obj -> {
            if (value.value.isEmpty()) "{}"
            else value.value.entries.joinToString("\n") { (key, itemValue) ->
                when (itemValue) {
                    is YamlValue.Obj -> "$pad$key:\n${yamlValueToDisplayString(itemValue, indent + 2)}"
                    else -> "$pad$key: ${yamlValueToDisplayString(itemValue)}"
                }
            }
        }
    }
}

private fun yamlValueToJsonString(value: YamlValue, indent: Int = 0): String {
    val pad = " ".repeat(indent)
    val childPad = " ".repeat(indent + 2)
    return when (value) {
        is YamlValue.Null -> "null"
        is YamlValue.Bool -> if (value.value) "true" else "false"
        is YamlValue.Int -> "${value.value}"
        is YamlValue.Double -> "${value.value}"
        is YamlValue.Str -> "\"${value.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is YamlValue.Array -> {
            if (value.value.isEmpty()) "[]"
            else "[\n${value.value.joinToString(",\n") { "$childPad${yamlValueToJsonString(it, indent + 2)}" }}\n$pad]"
        }
        is YamlValue.Obj -> {
            if (value.value.isEmpty()) "{}"
            else "{\n${value.value.entries.joinToString(",\n") { (k, v) ->
                "$childPad\"${k.replace("\"", "\\\"")}\": ${yamlValueToJsonString(v, indent + 2)}"
            }}\n$pad}"
        }
    }
}

private fun yamlValueToPlainText(value: YamlValue): String = when (value) {
    is YamlValue.Null -> ""
    is YamlValue.Bool -> if (value.value) "true" else "false"
    is YamlValue.Int -> "${value.value}"
    is YamlValue.Double -> "${value.value}"
    is YamlValue.Str -> value.value
    is YamlValue.Array -> value.value.joinToString("\n") { yamlValueToPlainText(it) }
    is YamlValue.Obj -> value.value.entries.joinToString("\n") { (k, v) -> "$k: ${yamlValueToPlainText(v)}" }
}

private fun editableBodyText(variant: ProjectVariant, body: YamlValue): String {
    return if (variant.isJsonBody()) {
        yamlValueToJsonString(body)
    } else {
        when (body) {
            is YamlValue.Null -> "null"
            is YamlValue.Bool -> if (body.value) "true" else "false"
            is YamlValue.Int -> "${body.value}"
            is YamlValue.Double -> "${body.value}"
            is YamlValue.Str -> body.value
            is YamlValue.Array -> yamlValueToJsonString(body)
            is YamlValue.Obj -> yamlValueToJsonString(body)
        }
    }
}

private fun parseEditableText(text: String, originalBody: YamlValue?): YamlValue {
    return when (originalBody) {
        null -> YamlValue.Str(text)
        is YamlValue.Null -> if (text.trim() == "null") YamlValue.Null else YamlValue.Str(text)
        is YamlValue.Bool -> text.toBooleanStrictOrNull()?.let(YamlValue::Bool) ?: YamlValue.Str(text)
        is YamlValue.Int -> text.toIntOrNull()?.let(YamlValue::Int) ?: YamlValue.Str(text)
        is YamlValue.Double -> text.toDoubleOrNull()?.let(YamlValue::Double) ?: YamlValue.Str(text)
        is YamlValue.Str -> YamlValue.Str(text)
        is YamlValue.Array,
        is YamlValue.Obj,
        -> YamlValue.Str(text)
    }
}

internal fun validateJsonBodyText(text: String): String? {
    return parseJsonBodyText(text).exceptionOrNull()?.let(::invalidJsonMessage)
}

internal fun parseJsonBodyText(text: String): Result<YamlValue> {
    return runCatching {
        jsonElementToYamlValue(bodyEditorJson.parseToJsonElement(text))
    }
}

internal fun formatJsonBodyText(text: String): Result<String> {
    return runCatching {
        val element = bodyEditorJson.parseToJsonElement(text)
        bodyEditorPrettyJson.encodeToString(JsonElement.serializer(), element)
    }
}

private fun invalidJsonMessage(error: Throwable): String {
    return error.message ?: error.toString()
}

private fun jsonElementToYamlValue(element: JsonElement): YamlValue = when (element) {
    is JsonNull -> YamlValue.Null
    is JsonPrimitive -> when {
        element.isString -> YamlValue.Str(element.content)
        element.booleanOrNull != null -> YamlValue.Bool(element.booleanOrNull!!)
        element.intOrNull != null -> YamlValue.Int(element.intOrNull!!)
        element.longOrNull != null -> YamlValue.Double(element.longOrNull!!.toDouble())
        element.doubleOrNull != null -> YamlValue.Double(element.doubleOrNull!!)
        else -> YamlValue.Str(element.content)
    }
    is JsonArray -> YamlValue.Array(element.map(::jsonElementToYamlValue))
    is JsonObject -> YamlValue.Obj(element.mapValues { (_, value) -> jsonElementToYamlValue(value) })
}

private fun ProjectVariant.isJsonBody(text: String? = null): Boolean {
    val contentType = headers
        ?.entries
        ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()

    if (contentType == "application/json" || contentType == "application/graphql-response+json" || contentType?.endsWith("+json") == true) {
        return true
    }

    if (body is YamlValue.Array || body is YamlValue.Obj) {
        return true
    }

    return text?.let { parseJsonBodyText(it).isSuccess } == true
}
