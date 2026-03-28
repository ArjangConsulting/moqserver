package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.displayAlias

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
        // Header
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

        // Metadata form
        EndpointMetadataForm(endpoint, onUpdateEndpoint)

        HorizontalDivider()

        // Variants
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
        // Method dropdown
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

        // Path
        OutlinedTextField(
            value = endpoint.path,
            onValueChange = { onUpdateEndpoint(endpoint.copy(path = it)) },
            label = { Text("Path") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }

    // Alias
    OutlinedTextField(
        value = endpoint.alias ?: endpoint.displayAlias,
        onValueChange = {
            onUpdateEndpoint(endpoint.copy(alias = it.ifBlank { null }))
        },
        label = { Text("Alias (display name)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // Auth section
    AuthConfigSection(
        auth = endpoint.auth,
        onUpdate = { onUpdateEndpoint(endpoint.copy(auth = it)) },
    )

    // Network section
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

// ── Variant Section ────────────────────────────────────────────────────────

@Composable
private fun VariantSection(
    endpoint: EndpointDocument,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    companionConnected: Boolean = false,
    onGenerateVariants: () -> Unit = {},
) {
    var selectedVariantIndex by remember(endpoint.id) {
        mutableStateOf(0)
    }

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
            OutlinedButton(onClick = {
                val newVariant = ProjectVariant(
                    name = "new-variant-${endpoint.variants.size + 1}",
                    status = 200,
                )
                val updated = endpoint.copy(variants = endpoint.variants + newVariant)
                onUpdateEndpoint(updated)
                selectedVariantIndex = updated.variants.size - 1
            }) {
                Text("Add Variant")
            }
        }
    }

    // Variant tabs
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        endpoint.variants.forEachIndexed { index, variant ->
            val isSelected = index == selectedVariantIndex
            val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            Text(
                text = variant.name + if (variant.isDefault == true) " *" else "",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .clickable { selectedVariantIndex = index }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    // Selected variant editor
    val variant = endpoint.variants.getOrNull(selectedVariantIndex)
    if (variant != null) {
        VariantEditor(
            variant = variant,
            onUpdate = { updated ->
                val newVariants = endpoint.variants.toMutableList()
                newVariants[selectedVariantIndex] = updated
                onUpdateEndpoint(endpoint.copy(variants = newVariants))
            },
            onRemove = {
                val newVariants = endpoint.variants.toMutableList()
                newVariants.removeAt(selectedVariantIndex)
                onUpdateEndpoint(endpoint.copy(variants = newVariants))
                selectedVariantIndex = (selectedVariantIndex - 1).coerceAtLeast(0)
            },
            canRemove = endpoint.variants.size > 1,
        )
    }
}

@Composable
private fun VariantEditor(
    variant: ProjectVariant,
    onUpdate: (ProjectVariant) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = variant.name,
                    onValueChange = { onUpdate(variant.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = variant.status.toString(),
                    onValueChange = {
                        val code = it.toIntOrNull() ?: return@OutlinedTextField
                        onUpdate(variant.copy(status = code))
                    },
                    label = { Text("Status") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp),
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
                    modifier = Modifier.width(120.dp),
                )
            }

            // Headers editor
            HeadersEditor(
                headers = variant.headers ?: emptyMap(),
                onUpdate = { onUpdate(variant.copy(headers = it.ifEmpty { null })) },
            )

            // Body source
            if (variant.bodyFile != null) {
                OutlinedTextField(
                    value = variant.bodyFile ?: "",
                    onValueChange = {
                        onUpdate(variant.copy(bodyFile = it.ifBlank { null }))
                    },
                    label = { Text("Body File (relative path)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("Body (inline JSON/YAML)", style = MaterialTheme.typography.titleSmall)
                val bodyText = remember(variant.body) {
                    variant.body?.let { yamlValueToDisplayString(it) } ?: ""
                }
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { /* Read-only for now — JsonCodeEditor will replace this */ },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
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
}

@Composable
private fun HeadersEditor(
    headers: Map<String, String>,
    onUpdate: (Map<String, String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Headers", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.TextButton(onClick = {
                onUpdate(headers + ("" to ""))
            }) {
                Text("Add")
            }
        }

        headers.entries.forEachIndexed { index, (key, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { newKey ->
                        val entries = headers.entries.toMutableList()
                        entries[index] = object : Map.Entry<String, String> {
                            override val key = newKey
                            override val value = value
                        }
                        onUpdate(entries.associate { it.key to it.value })
                    },
                    placeholder = { Text("Key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { newVal ->
                        onUpdate(headers.toMutableMap().apply { put(key, newVal) })
                    },
                    placeholder = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(onClick = {
                    onUpdate(headers.toMutableMap().apply { remove(key) })
                }) {
                    Text("x", color = MaterialTheme.colorScheme.error)
                }
            }
        }
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
            else value.value.entries.joinToString("\n") { (k, v) ->
                when (v) {
                    is YamlValue.Obj -> "$pad$k:\n${yamlValueToDisplayString(v, indent + 2)}"
                    else -> "$pad$k: ${yamlValueToDisplayString(v)}"
                }
            }
        }
    }
}
