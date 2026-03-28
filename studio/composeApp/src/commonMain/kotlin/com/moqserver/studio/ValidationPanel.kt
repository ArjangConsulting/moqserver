package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.projectformat.ValidationDiagnostic

@Composable
fun ValidationPanel(
    diagnostics: List<ValidationDiagnostic>,
    onDiagnosticClick: (ValidationDiagnostic) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = diagnostics.filter { it.severity == ValidationDiagnostic.Severity.ERROR }
    val warnings = diagnostics.filter { it.severity == ValidationDiagnostic.Severity.WARNING }

    Column(modifier = modifier) {
        // Summary bar
        Surface(
            color = when {
                errors.isNotEmpty() -> MaterialTheme.colorScheme.errorContainer
                warnings.isNotEmpty() -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Validation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (diagnostics.isEmpty()) {
                    Text(
                        "No issues",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    if (errors.isNotEmpty()) {
                        SeverityBadge("${errors.size} error${if (errors.size > 1) "s" else ""}", isError = true)
                    }
                    if (warnings.isNotEmpty()) {
                        SeverityBadge("${warnings.size} warning${if (warnings.size > 1) "s" else ""}", isError = false)
                    }
                }
            }
        }

        HorizontalDivider()

        if (diagnostics.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {
                items(diagnostics) { diagnostic ->
                    DiagnosticRow(
                        diagnostic = diagnostic,
                        onClick = { onDiagnosticClick(diagnostic) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    diagnostic: ValidationDiagnostic,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(
            text = if (diagnostic.severity == ValidationDiagnostic.Severity.ERROR) "E" else "W",
            isError = diagnostic.severity == ValidationDiagnostic.Severity.ERROR,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = diagnostic.message,
                style = MaterialTheme.typography.bodySmall,
            )
            val location = buildString {
                diagnostic.file?.let { append(it) }
                diagnostic.field?.let { append(" > $it") }
            }
            if (location.isNotEmpty()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(text: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
