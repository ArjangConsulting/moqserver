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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private object ValidationPanelStrings {
    const val VALIDATION = "Validation"
    const val NO_ISSUES = "No issues"
    const val SEVERITY_ERROR = "E"
    const val SEVERITY_WARNING = "W"
    const val NAVIGATE_ARROW = "→"
    const val VARIANT_PREFIX = "Variant: "
    const val FIELD_SEPARATOR = " · "
    const val DISMISS_ERROR = "Dismiss error"
}

@Composable
fun ValidationPanel(
    diagnostics: List<ValidationDiagnostic>,
    transientDiagnostic: ValidationDiagnostic? = null,
    onDiagnosticClick: (ValidationDiagnostic) -> Unit,
    onDismissTransientDiagnostic: () -> Unit = {},
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xl, vertical = StudioDimens.m),
                horizontalArrangement = Arrangement.spacedBy(StudioDimens.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ValidationPanelStrings.VALIDATION,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (diagnostics.isEmpty()) {
                    Text(
                        ValidationPanelStrings.NO_ISSUES,
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
                        dismissible = diagnostic == transientDiagnostic,
                        onClick = { onDiagnosticClick(diagnostic) },
                        onDismiss = onDismissTransientDiagnostic,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    diagnostic: ValidationDiagnostic,
    dismissible: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = StudioDimens.xl, vertical = StudioDimens.s),
        horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(
            text = if (diagnostic.severity == ValidationDiagnostic.Severity.ERROR) {
                ValidationPanelStrings.SEVERITY_ERROR
            } else {
                ValidationPanelStrings.SEVERITY_WARNING
            },
            isError = diagnostic.severity == ValidationDiagnostic.Severity.ERROR,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = diagnostic.message,
                style = MaterialTheme.typography.bodySmall,
            )
            val location = buildString {
                diagnostic.endpointLabel?.let { append(it) }
                    ?: diagnostic.file?.let { append(it) }
                diagnostic.variantName?.let {
                    append(ValidationPanelStrings.FIELD_SEPARATOR)
                    append(ValidationPanelStrings.VARIANT_PREFIX)
                    append(it)
                }
                diagnostic.field?.let {
                    append(ValidationPanelStrings.FIELD_SEPARATOR)
                    append(it)
                }
            }
            if (location.isNotEmpty()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (dismissible) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.Top).padding(top = StudioDimens.xxs),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = ValidationPanelStrings.DISMISS_ERROR,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (diagnostic.endpointId != null) {
            Text(
                text = ValidationPanelStrings.NAVIGATE_ARROW,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
            .clip(RoundedCornerShape(StudioDimens.xs))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = StudioDimens.s, vertical = StudioDimens.xxs),
    )
}
