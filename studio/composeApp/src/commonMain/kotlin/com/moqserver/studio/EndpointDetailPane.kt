package com.moqserver.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.editor.JsonCodeEditor
import com.moqserver.studio.projectformat.*

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

@Composable
fun EndpointDetailPane(
    endpoint: EndpointDocument,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    companionConnected: Boolean = false,
    onGenerateVariants: () -> Unit = {},
) {
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
            Text(endpoint.path, style = MaterialTheme.typography.titleLarge)
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
        EndpointMetadataForm(endpoint, onUpdateEndpoint)
        HorizontalDivider()
        VariantSection(endpoint, onUpdateEndpoint, companionConnected, onGenerateVariants)
    }
}

@Composable
private fun EndpointMetadataForm(
    endpoint: EndpointDocument,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
) {
    Text("Metadata", style = MaterialTheme.typography.titleMedium)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var methodExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = endpoint.method,
                onValueChange = {},
                label = { Text("Method") },
                readOnly = true,
                modifier = Modifier.width(120.dp).clickable { methodExpanded = true },
            )
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
                    modifier = Modifier.width(160.dp).clickable { typeExpanded = true },
                )
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Verify", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = auth.verify,
                onCheckedChange = { onUpdate(auth.copy(verify = it)) },
            )
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
                label = { Text("Latency (ms)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = (network.jitterMs ?: 0).toString(),
                onValueChange = {
                    val ms = it.toIntOrNull() ?: 0
                    onUpdate(network.copy(jitterMs = ms))
                },
                label = { Text("Jitter (ms)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = (network.packetLossPercent ?: 0.0).toString(),
                onValueChange = {
                    val pct = it.toDoubleOrNull() ?: 0.0
                    onUpdate(network.copy(packetLossPercent = pct))
                },
                label = { Text("Packet Loss %") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VariantSection(
    endpoint: EndpointDocument,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
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
        if (variant.isPristine()) removeVariant(index) else variantIndexToDelete = index
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
                        onUpdate = { updated ->
                            onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                        },
                    )

                    VariantDetailTab.CRITERIA -> CriteriaTab(
                        requestRules = requestRules,
                        onUpdate = { updatedRules ->
                            onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                        },
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
private fun TabStrip(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onClose != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (onClose != null) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Delete variant",
                tint = contentColor,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onClose),
            )
        }
    }
}

@Composable
private fun StatusBadge(status: Int) {
    val color = when {
        status in 200..299 -> Color(0xFF2E7D32) // green
        status in 300..399 -> Color(0xFF7B5E00) // amber — orange closer to green
        status in 400..499 -> Color(0xFFBF360C) // deep orange-red — orange closer to red
        status >= 500 -> Color(0xFFC62828)       // red
        else -> Color(0xFF616161)
    }
    Text(
        text = "$status",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTooltip(text: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Info",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
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
        val nameConflict = variant.name.isNotBlank() && variant.name in siblingNames
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = variant.name,
                onValueChange = { onUpdate(variant.copy(name = it)) },
                label = { Text("Variant Name") },
                singleLine = true,
                isError = nameConflict,
                supportingText = if (nameConflict) {
                    { Text("Name must be unique within this endpoint") }
                } else null,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = variant.status.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { status ->
                        onUpdate(variant.copy(status = status))
                    }
                },
                label = { Text("Status") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = variant.isDefault == true,
                onCheckedChange = { onUpdate(variant.copy(isDefault = if (it) true else null)) },
            )
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = (variant.delayMs ?: 0).toString(),
                onValueChange = {
                    val ms = it.toIntOrNull()
                    onUpdate(variant.copy(delayMs = if (ms != null && ms > 0) ms else null))
                },
                label = { Text("Delay (ms)") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }

        val bodySource = when {
            variant.bodyFile != null -> "Fixture file: ${variant.bodyFile}"
            variant.body != null -> "Inline body"
            else -> "Empty response"
        }
        Text(
            text = bodySource,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        AuthConfigSection(
            auth = endpoint.auth,
            onUpdate = { onUpdateEndpoint(endpoint.copy(auth = it)) },
        )

        NetworkSection(
            network = endpoint.network,
            onUpdate = { onUpdateEndpoint(endpoint.copy(network = it)) },
        )

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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Request Cookies", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.TextButton(onClick = {
                onUpdate(requestRules.copy(cookies = cookies + RuleMatcher(name = "", required = true)))
            }) {
                Text("Add")
            }
        }

        if (cookies.isEmpty()) {
            Text(
                "No cookies configured. Add a cookie to set up cookie-based matching criteria.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        cookies.forEachIndexed { index, cookie ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = cookie.name,
                        onValueChange = { newName ->
                            onUpdate(requestRules.copy(cookies = cookies.updated(index, cookie.copy(name = newName))))
                        },
                        label = { Text("Cookie Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = {
                        val updated = cookies.toMutableList().also { it.removeAt(index) }
                        onUpdate(requestRules.copy(cookies = updated.ifEmpty { null }))
                    }) {
                        Text("x", color = MaterialTheme.colorScheme.error)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val matchType = cookie.effectiveMatchType
                    var criteriaExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = matchType.displayName,
                            onValueChange = {},
                            label = { Text("Criteria") },
                            readOnly = true,
                            modifier = Modifier.width(160.dp).clickable { criteriaExpanded = true },
                        )
                        DropdownMenu(expanded = criteriaExpanded, onDismissRequest = { criteriaExpanded = false }) {
                            MatchType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = {
                                        val updated = cookie.copy(
                                            matchType = type,
                                            match = if (type == MatchType.REQUIRE) null else cookie.match,
                                        )
                                        onUpdate(requestRules.copy(cookies = cookies.updated(index, updated)))
                                        criteriaExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (matchType.needsValue) {
                        OutlinedTextField(
                            value = cookie.match ?: "",
                            onValueChange = { newMatch ->
                                onUpdate(requestRules.copy(cookies = cookies.updated(index, cookie.copy(match = newMatch.ifBlank { null }))))
                            },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (cookies.isNotEmpty()) {
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Verify Cookies", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = requestRules.verifyCookies == true,
                    onCheckedChange = { onUpdate(requestRules.copy(verifyCookies = if (it) true else null)) },
                )
            }
        }
    }
}

@Composable
private fun BodyTab(
    variant: ProjectVariant,
    onUpdate: (ProjectVariant) -> Unit,
) {
    val bodyFile = variant.bodyFile
    val body = variant.body

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            bodyFile != null -> {
                OutlinedTextField(
                    value = bodyFile,
                    onValueChange = { onUpdate(variant.copy(bodyFile = it.ifBlank { null })) },
                    label = { Text("Body File (relative path)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            body != null -> {
                var selectedFormat by remember(variant.name) { mutableStateOf(BodyFormat.RAW) }

                TabStrip {
                    BodyFormat.entries.forEach { format ->
                        TabChip(
                            label = format.label,
                            selected = format == selectedFormat,
                            onClick = { selectedFormat = format },
                        )
                    }
                }

                when (selectedFormat) {
                    BodyFormat.JSON -> {
                        JsonCodeEditor(
                            text = yamlValueToJsonString(body),
                            onTextChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                        )
                    }
                    BodyFormat.PLAIN_TEXT -> {
                        OutlinedTextField(
                            value = yamlValueToPlainText(body),
                            onValueChange = {},
                            readOnly = true,
                            minLines = 6,
                            maxLines = 16,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BodyFormat.RAW -> {
                        OutlinedTextField(
                            value = yamlValueToDisplayString(body),
                            onValueChange = {},
                            readOnly = true,
                            minLines = 6,
                            maxLines = 16,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
private fun CriteriaTab(
    requestRules: RequestRules,
    onUpdate: (RequestRules) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RuleMatcherEditor(
            title = "Query Parameters",
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Response Headers", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.TextButton(onClick = {
                onUpdateHeaders(headers + ("" to ""))
            }) {
                Text("Add")
            }
        }

        if (headers.isEmpty()) {
            Text(
                "No response headers configured for this variant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        headers.entries.forEachIndexed { index, (key, value) ->
            val ruleMatcher = headerCriteria.find { it.name.equals(key, ignoreCase = true) }
            val isConditionable = ruleMatcher != null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            if (isConditionable) {
                                val updatedCriteria = headerCriteria.map {
                                    if (it.name.equals(key, ignoreCase = true)) it.copy(name = newKey) else it
                                }
                                onUpdateRules(requestRules.copy(headers = updatedCriteria))
                            }
                        },
                        placeholder = { Text("Header") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { newValue ->
                            val updated = headers.entries.mapIndexed { entryIndex, entry ->
                                if (entryIndex == index) newValue else entry.value
                            }
                            onUpdateHeaders(headers.keys.zip(updated).associate { it.first to it.second })
                        },
                        placeholder = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = {
                        val updatedHeaders = headers.toMutableMap().apply { remove(key) }
                        onUpdateHeaders(updatedHeaders)
                        if (isConditionable) {
                            onUpdateRules(
                                requestRules.copy(
                                    headers = headerCriteria
                                        .filter { !it.name.equals(key, ignoreCase = true) }
                                        .ifEmpty { null },
                                ),
                            )
                        }
                    }) {
                        Text("x", color = MaterialTheme.colorScheme.error)
                    }
                }

                // Conditionable section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = isConditionable,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onUpdateRules(requestRules.copy(headers = headerCriteria + RuleMatcher(name = key, required = true)))
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
                    Text("Condition", style = MaterialTheme.typography.bodySmall)

                    if (isConditionable && ruleMatcher != null) {
                        Spacer(Modifier.width(8.dp))
                        val matchType = ruleMatcher.effectiveMatchType
                        var criteriaExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = matchType.displayName,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.width(160.dp).clickable { criteriaExpanded = true },
                            )
                            DropdownMenu(expanded = criteriaExpanded, onDismissRequest = { criteriaExpanded = false }) {
                                MatchType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.displayName) },
                                        onClick = {
                                            val updated = ruleMatcher.copy(
                                                matchType = type,
                                                match = if (type == MatchType.REQUIRE) null else ruleMatcher.match,
                                            )
                                            onUpdateRules(
                                                requestRules.copy(
                                                    headers = headerCriteria.map {
                                                        if (it.name.equals(key, ignoreCase = true)) updated else it
                                                    },
                                                ),
                                            )
                                            criteriaExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        if (matchType.needsValue) {
                            OutlinedTextField(
                                value = ruleMatcher.match ?: "",
                                onValueChange = { newMatch ->
                                    val updated = ruleMatcher.copy(match = newMatch.ifBlank { null })
                                    onUpdateRules(
                                        requestRules.copy(
                                            headers = headerCriteria.map {
                                                if (it.name.equals(key, ignoreCase = true)) updated else it
                                            },
                                        ),
                                    )
                                },
                                placeholder = { Text("Match value") },
                                singleLine = true,
                                modifier = Modifier.width(180.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleMatcherEditor(
    title: String,
    items: List<RuleMatcher>,
    onUpdate: (List<RuleMatcher>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.TextButton(onClick = {
                onUpdate(items + RuleMatcher(name = "", required = true))
            }) {
                Text("Add")
            }
        }

        if (items.isEmpty()) {
            Text(
                "No criteria configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items.forEachIndexed { index, item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = item.name,
                        onValueChange = { newName ->
                            onUpdate(items.updated(index, item.copy(name = newName)))
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = item.match ?: "",
                        onValueChange = { newMatch ->
                            onUpdate(
                                items.updated(
                                    index,
                                    item.copy(match = newMatch.ifBlank { null }),
                                ),
                            )
                        },
                        label = { Text("Match") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = {
                        onUpdate(items.toMutableList().also { it.removeAt(index) })
                    }) {
                        Text("x", color = MaterialTheme.colorScheme.error)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Required", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = item.required != false,
                        onCheckedChange = { required ->
                            onUpdate(items.updated(index, item.copy(required = if (required) true else null)))
                        },
                    )
                }
            }
        }
    }
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
            it.verifyCookies == true ||
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

private val MatchType.displayName: String
    get() = when (this) {
        MatchType.REQUIRE -> "Require"
        MatchType.EQUAL_TO -> "Equal to"
        MatchType.CONTAINS -> "Contains"
        MatchType.BEGINS_WITH -> "Begins with"
        MatchType.ENDS_WITH -> "Ends with"
        MatchType.NOT_EQUAL_TO -> "Not equal to"
        MatchType.NOT_CONTAINS -> "Not contains"
    }

private val MatchType.needsValue: Boolean
    get() = this != MatchType.REQUIRE

private val RuleMatcher.effectiveMatchType: MatchType
    get() = matchType ?: if (match != null) MatchType.EQUAL_TO else MatchType.REQUIRE

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
