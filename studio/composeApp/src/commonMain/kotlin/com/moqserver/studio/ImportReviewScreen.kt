package com.moqserver.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.ui.MethodBadge

@Composable
fun ImportReviewScreen(
    state: ImportState,
    onToggleEndpoint: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onUpdateProjectName: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(24.dp)) {
        // Header
        Text(
            text = when (state.source) {
                ImportSourceType.OPENAPI -> "Import OpenAPI Spec"
                ImportSourceType.HAR -> "Import HAR File"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${state.sourceFileName} — ${state.parsedSpec.title} v${state.parsedSpec.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Warnings
        if (state.warnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Warnings",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    for (warning in state.warnings.take(5)) {
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (state.warnings.size > 5) {
                        Text(
                            text = "...and ${state.warnings.size - 5} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Project name
        OutlinedTextField(
            value = state.projectName,
            onValueChange = onUpdateProjectName,
            label = { Text("Project Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.5f),
        )

        Spacer(Modifier.height(16.dp))

        // Summary and select/deselect
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.acceptedCount} of ${state.totalCount} endpoints selected",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectAll) { Text("Select All") }
                TextButton(onClick = onDeselectAll) { Text("Deselect All") }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Endpoint list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(state.entries) { index, entry ->
                ImportEndpointRow(
                    endpoint = entry.endpoint,
                    accepted = entry.accepted,
                    onToggle = { onToggleEndpoint(index) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onConfirm,
                enabled = state.acceptedCount > 0 && state.projectName.isNotBlank(),
            ) {
                Text("Import")
            }
        }
    }
}

@Composable
private fun ImportEndpointRow(
    endpoint: ParsedEndpoint,
    accepted: Boolean,
    onToggle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (accepted) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { onToggle() },
                )
                MethodBadge(endpoint.method)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = endpoint.path,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${endpoint.responses.size} variant${if (endpoint.responses.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Details")
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (endpoint.authType != AuthType.NONE) {
                        Text(
                            "Auth: ${endpoint.authType.name.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    for (resp in endpoint.responses) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${resp.statusCode}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                resp.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            resp.headers["Content-Type"]?.let { ct ->
                                Text(
                                    ct,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
