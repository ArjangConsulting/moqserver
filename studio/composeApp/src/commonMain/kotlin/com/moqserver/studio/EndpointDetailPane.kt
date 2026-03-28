package com.moqserver.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moqserver.studio.editor.JsonCodeEditor
import com.moqserver.studio.projectformat.*
import com.moqserver.studio.ui.InfoTooltip
import com.moqserver.studio.ui.MatchConditionEditor
import com.moqserver.studio.ui.MethodBadge
import com.moqserver.studio.ui.StatusBadge
import com.moqserver.studio.ui.TabChip
import com.moqserver.studio.ui.TabStrip

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
    projectPath: String = "",
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
        VariantSection(endpoint, onUpdateEndpoint, projectPath, companionConnected, onGenerateVariants)
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
                label = { Text("Latency (ms)") },
                singleLine = true,
                trailingIcon = { InfoTooltip("Fixed delay in milliseconds added to every response.") },
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
                trailingIcon = { InfoTooltip("Random variation in milliseconds added to the latency to simulate an unstable connection.") },
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
                trailingIcon = { InfoTooltip("Percentage of requests that will be dropped to simulate packet loss (0–100).") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VariantSection(
    endpoint: EndpointDocument,
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
        val nameConflict = variant.name.isNotBlank() && variant.name in siblingNames
        val statusError = variant.status !in 100..599
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
                trailingIcon = { InfoTooltip("Display name for this variant. Must be unique within the endpoint.") },
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
                isError = statusError,
                supportingText = if (statusError) { { Text("100–599") } } else null,
                trailingIcon = { InfoTooltip("HTTP status code this variant returns (100–599).") },
                modifier = Modifier.width(130.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default", style = MaterialTheme.typography.bodySmall)
            InfoTooltip("When enabled, this variant is returned by default when no matching criteria exist.")
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
                trailingIcon = { InfoTooltip("Artificial delay in milliseconds before the response is sent.") },
                modifier = Modifier.width(160.dp),
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

                MatchConditionEditor(
                    ruleMatcher = cookie,
                    onUpdate = { updated ->
                        onUpdate(requestRules.copy(cookies = cookies.updated(index, updated)))
                    },
                    dropdownWidth = 160.dp,
                    valueWidth = null,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    projectPath: String = "",
    onUpdate: (ProjectVariant) -> Unit,
) {
    val bodyFile = variant.bodyFile
    val body = variant.body

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            bodyFile != null -> {
                val fileContent = remember(projectPath, bodyFile) {
                    runCatching {
                        java.io.File(projectPath, bodyFile).takeIf { it.isFile }?.readText()
                    }.getOrNull()
                }

                Text(
                    text = bodyFile,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (fileContent != null) {
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
                                text = fileContent,
                                onTextChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().height(280.dp),
                            )
                        }
                        BodyFormat.PLAIN_TEXT, BodyFormat.RAW -> {
                            OutlinedTextField(
                                value = fileContent,
                                onValueChange = {},
                                readOnly = true,
                                minLines = 6,
                                maxLines = 16,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    Text(
                        text = "File not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
    auth: ProjectAuthConfig?,
    network: NetworkBehavior?,
    onUpdate: (RequestRules) -> Unit,
    onUpdateAuth: (ProjectAuthConfig?) -> Unit,
    onUpdateNetwork: (NetworkBehavior?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RuleMatcherEditor(
            title = "Query Parameters",
            items = requestRules.queryParams ?: emptyList(),
            onUpdate = { queryParams ->
                onUpdate(requestRules.copy(queryParams = queryParams.ifEmpty { null }))
            },
        )

        HorizontalDivider()

        AuthConfigSection(auth = auth, onUpdate = onUpdateAuth)

        NetworkSection(network = network, onUpdate = onUpdateNetwork)
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
    val hasAnyRequired = headersList.any { (key, _) ->
        headerCriteria.any { it.name.equals(key, ignoreCase = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Header Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(80.dp),
                )
                if (hasAnyRequired) {
                    Text(
                        "Condition",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(140.dp),
                    )
                    Text(
                        "Match Value",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(160.dp),
                    )
                }
                Spacer(Modifier.width(40.dp))
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
                        placeholder = { Text("Name") },
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
                    Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = isRequired,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onUpdateRules(
                                        requestRules.copy(
                                            headers = headerCriteria + RuleMatcher(name = key, required = true),
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
                    if (hasAnyRequired) {
                        if (ruleMatcher != null) {
                            MatchConditionEditor(
                                ruleMatcher = ruleMatcher,
                                onUpdate = { updated ->
                                    onUpdateRules(
                                        requestRules.copy(
                                            headers = headerCriteria.map {
                                                if (it.name.equals(key, ignoreCase = true)) updated else it
                                            },
                                        ),
                                    )
                                },
                                showValuePlaceholder = true,
                            )
                        } else {
                            Spacer(Modifier.width(140.dp + 4.dp + 160.dp))
                        }
                    }
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val updatedHeaders = headers.toMutableMap().apply { remove(key) }
                            onUpdateHeaders(updatedHeaders)
                            if (isRequired) {
                                onUpdateRules(
                                    requestRules.copy(
                                        headers = headerCriteria
                                            .filter { !it.name.equals(key, ignoreCase = true) }
                                            .ifEmpty { null },
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Text("x", color = MaterialTheme.colorScheme.error)
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
