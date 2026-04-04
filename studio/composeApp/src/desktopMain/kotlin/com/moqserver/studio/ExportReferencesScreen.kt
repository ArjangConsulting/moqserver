package com.moqserver.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.composeapp.generated.resources.Res
import com.moqserver.composeapp.generated.resources.logo_java
import com.moqserver.composeapp.generated.resources.logo_javascript
import com.moqserver.composeapp.generated.resources.logo_kotlin
import com.moqserver.composeapp.generated.resources.logo_swift
import com.moqserver.studio.export.ExportLanguage
import org.jetbrains.compose.resources.painterResource

private object ExportStrings {
    const val TITLE = "Export References"
    const val SUBTITLE = "Generate source code constants from your project's endpoint and variant reference names."
    const val SELECT_LANGUAGES = "Languages"
    const val KOTLIN_PACKAGE = "Kotlin Package"
    const val KOTLIN_PACKAGE_PLACEHOLDER = "e.g. com.example.api"
    const val JAVA_PACKAGE = "Java Package"
    const val JAVA_PACKAGE_PLACEHOLDER = "e.g. com.example.api"
    const val INCLUDE_API_DESCRIPTIONS = "Include API descriptions in generated docs"
    const val INCLUDE_VARIANT_DESCRIPTIONS = "Include variant descriptions in generated docs"
    const val DESTINATION = "Destination Folder"
    const val CHOOSE_FOLDER = "Choose..."
    const val NO_FOLDER_SELECTED = "No folder selected"
    const val EXPORT = "Export"
    const val CANCEL = "Cancel"
}

data class ExportReferencesState(
    val selectedLanguages: Set<ExportLanguage> = emptySet(),
    val kotlinPackage: String = "",
    val javaPackage: String = "",
    val includeApiDescriptions: Boolean = true,
    val includeVariantDescriptions: Boolean = true,
    val destinationFolder: String? = null,
) {
    val canExport: Boolean
        get() = selectedLanguages.isNotEmpty() && destinationFolder != null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportReferencesScreen(
    state: ExportReferencesState,
    onStateChange: (ExportReferencesState) -> Unit,
    onChooseFolder: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(StudioDimens.xxxl),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.xl),
        ) {
            ExportHeader()
            HorizontalDivider()
            LanguageSelectionSection(state, onStateChange)
            PackageFieldsSection(state, onStateChange)
            DescriptionsToggle(state, onStateChange)
            DestinationSection(state, onChooseFolder)
            Spacer(modifier = Modifier.height(StudioDimens.l))
            ActionButtons(canExport = state.canExport, onExport = onExport, onCancel = onCancel)
        }
    }
}

@Composable
private fun ExportHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
        Text(ExportStrings.TITLE, style = MaterialTheme.typography.headlineSmall)
        Text(
            ExportStrings.SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSelectionSection(
    state: ExportReferencesState,
    onStateChange: (ExportReferencesState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
        Text(
            ExportStrings.SELECT_LANGUAGES,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
        ) {
            ExportLanguage.entries.forEach { language ->
                LanguageCard(
                    language = language,
                    selected = language in state.selectedLanguages,
                    onClick = {
                        val updated = if (language in state.selectedLanguages) {
                            state.selectedLanguages - language
                        } else {
                            state.selectedLanguages + language
                        }
                        onStateChange(state.copy(selectedLanguages = updated))
                    },
                )
            }
        }
    }
}

@Composable
private fun PackageFieldsSection(
    state: ExportReferencesState,
    onStateChange: (ExportReferencesState) -> Unit,
) {
    val showKotlin = ExportLanguage.KOTLIN in state.selectedLanguages
    val showJava = ExportLanguage.JAVA in state.selectedLanguages
    if (!showKotlin && !showJava) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(StudioDimens.xl),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.l),
        ) {
            if (showKotlin) {
                OutlinedTextField(
                    value = state.kotlinPackage,
                    onValueChange = { onStateChange(state.copy(kotlinPackage = it)) },
                    label = { Text(ExportStrings.KOTLIN_PACKAGE) },
                    placeholder = { Text(ExportStrings.KOTLIN_PACKAGE_PLACEHOLDER) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showJava) {
                OutlinedTextField(
                    value = state.javaPackage,
                    onValueChange = { onStateChange(state.copy(javaPackage = it)) },
                    label = { Text(ExportStrings.JAVA_PACKAGE) },
                    placeholder = { Text(ExportStrings.JAVA_PACKAGE_PLACEHOLDER) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DescriptionsToggle(
    state: ExportReferencesState,
    onStateChange: (ExportReferencesState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
        ) {
            Checkbox(
                checked = state.includeApiDescriptions,
                onCheckedChange = { onStateChange(state.copy(includeApiDescriptions = it)) },
            )
            Text(ExportStrings.INCLUDE_API_DESCRIPTIONS, style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
        ) {
            Checkbox(
                checked = state.includeVariantDescriptions,
                onCheckedChange = { onStateChange(state.copy(includeVariantDescriptions = it)) },
            )
            Text(ExportStrings.INCLUDE_VARIANT_DESCRIPTIONS, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DestinationSection(
    state: ExportReferencesState,
    onChooseFolder: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(StudioDimens.xl),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
        ) {
            Text(
                ExportStrings.DESTINATION,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
            ) {
                Text(
                    text = state.destinationFolder ?: ExportStrings.NO_FOLDER_SELECTED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.destinationFolder != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onChooseFolder) {
                    Text(ExportStrings.CHOOSE_FOLDER)
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    canExport: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) {
            Text(ExportStrings.CANCEL)
        }
        Spacer(modifier = Modifier.width(StudioDimens.m))
        Button(
            onClick = onExport,
            enabled = canExport,
        ) {
            Text(ExportStrings.EXPORT)
        }
    }
}

@Composable
private fun LanguageCard(
    language: ExportLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = StudioDimens.l, vertical = StudioDimens.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
        ) {
            Image(
                painter = languagePainter(language),
                contentDescription = language.displayName,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun languagePainter(language: ExportLanguage): Painter = when (language) {
    ExportLanguage.KOTLIN -> painterResource(Res.drawable.logo_kotlin)
    ExportLanguage.JAVA -> painterResource(Res.drawable.logo_java)
    ExportLanguage.JAVASCRIPT -> painterResource(Res.drawable.logo_javascript)
    ExportLanguage.SWIFT -> painterResource(Res.drawable.logo_swift)
}
