package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.editor.JsonCodeEditor
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.defaultVariantBaseName
import com.moqserver.studio.projectformat.displayAlias
import com.moqserver.studio.projectformat.suggestedVariantName

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

    AuthConfigSection(
        auth = endpoint.auth,
        onUpdate = { onUpdateEndpoint(endpoint.copy(auth = it)) },
    )

    NetworkSection(
        network = endpoint.network,
        onUpdate = { onUpdateEndpoint(endpoint.copy(network = it)) },
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
        Text("Auth Override", style = MaterialTheme.typography.titleSmall)
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
                    value = auth.type.name.lowercase().replace("_", "-"),
                    onValueChange = {},
                    label = { Text("Type") },
                    readOnly = true,
                    modifier = Modifier.width(140.dp).clickable { typeExpanded = true },
                )
                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    AuthType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name.lowercase().replace("_", "-")) },
                            onClick = {
                                onUpdate(auth.copy(type = type))
                                typeExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = auth.headerName ?: "",
                onValueChange = { onUpdate(auth.copy(headerName = it.ifBlank { null })) },
                label = { Text("Header Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Variants (${endpoint.variants.size})", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (companionConnected) {
                androidx.compose.material3.FilledTonalButton(onClick = onGenerateVariants) {
                    Text("AI Generate")
                }
            }
            OutlinedButton(
                onClick = {
                    val newStatus = defaultNewVariantStatus(endpoint.variants)
                    val newVariant = ProjectVariant(
                        name = suggestedVariantName(
                            status = newStatus,
                            existingNames = endpoint.variants.map(ProjectVariant::name),
                        ),
                        status = newStatus,
                    )
                    val updated = endpoint.copy(variants = endpoint.variants + newVariant)
                    onUpdateEndpoint(updated)
                    selectedVariantIndex = updated.variants.lastIndex
                    selectedTab = VariantDetailTab.SUMMARY
                },
            ) {
                Text("Add Variant")
            }
        }
    }

    VariantTabs(
        variants = endpoint.variants,
        selectedIndex = activeVariantIndex,
        onSelect = { selectedVariantIndex = it },
    )

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
                        siblingNames = endpoint.variants.mapIndexedNotNull { index, item ->
                            item.name.takeUnless { index == activeVariantIndex }
                        },
                        onUpdate = { updated ->
                            onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                        },
                        onRemove = {
                            val updatedVariants = endpoint.variants.toMutableList().also { it.removeAt(activeVariantIndex) }
                            onUpdateEndpoint(endpoint.copy(variants = updatedVariants))
                            selectedVariantIndex = (activeVariantIndex - 1).coerceAtLeast(0)
                        },
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
            )
        }
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

    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun StatusBadge(status: Int) {
    val color = when {
        status in 200..299 -> Color(0xFF2E7D32)
        status in 300..399 -> Color(0xFF1565C0)
        status in 400..499 -> Color(0xFFE65100)
        status >= 500 -> Color(0xFFC62828)
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

@Composable
private fun VariantSummaryTab(
    endpoint: EndpointDocument,
    variant: ProjectVariant,
    siblingNames: List<String>,
    onUpdate: (ProjectVariant) -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = variant.name,
                onValueChange = { onUpdate(variant.copy(name = it.ifBlank { defaultVariantBaseName(variant.status) })) },
                label = { Text("Variant Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = variant.status.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { status ->
                        val updatedName = if (variant.name.isGeneratedVariantName(variant.status)) {
                            suggestedVariantName(
                                status = status,
                                existingNames = siblingNames,
                            )
                        } else {
                            variant.name
                        }
                        onUpdate(variant.copy(name = updatedName, status = status))
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

private fun String.isGeneratedVariantName(status: Int): Boolean {
    val escapedBaseName = Regex.escape(defaultVariantBaseName(status))
    return Regex("^$escapedBaseName(?: \\d+)?$").matches(this)
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
