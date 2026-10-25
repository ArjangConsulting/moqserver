package com.moqserver.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SelectableCard
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeCheckbox
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import org.jetbrains.compose.resources.painterResource

private object ExportStrings {
	const val TITLE = "Generate Client Code"
	const val SUBTITLE = "Generate source code references from your project's endpoints and variants."
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
        FlowLayout(
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

    ThemeCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
            if (showKotlin) {
                FormTextField(
                    value = state.kotlinPackage,
                    onValueChange = { onStateChange(state.copy(kotlinPackage = it)) },
                    label = ExportStrings.KOTLIN_PACKAGE,
                    placeholder = ExportStrings.KOTLIN_PACKAGE_PLACEHOLDER,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showJava) {
                FormTextField(
                    value = state.javaPackage,
                    onValueChange = { onStateChange(state.copy(javaPackage = it)) },
                    label = ExportStrings.JAVA_PACKAGE,
                    placeholder = ExportStrings.JAVA_PACKAGE_PLACEHOLDER,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
    }
}

@Composable
private fun DescriptionsToggle(
    state: ExportReferencesState,
    onStateChange: (ExportReferencesState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
        ThemeCheckbox(
            checked = state.includeApiDescriptions,
            onCheckedChange = { onStateChange(state.copy(includeApiDescriptions = it)) },
            label = ExportStrings.INCLUDE_API_DESCRIPTIONS,
        )
        ThemeCheckbox(
            checked = state.includeVariantDescriptions,
            onCheckedChange = { onStateChange(state.copy(includeVariantDescriptions = it)) },
            label = ExportStrings.INCLUDE_VARIANT_DESCRIPTIONS,
        )
    }
}

@Composable
private fun DestinationSection(
    state: ExportReferencesState,
    onChooseFolder: () -> Unit,
) {
    SectionCard(
        title = ExportStrings.DESTINATION,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                PrimaryButton(
                    text = ExportStrings.CHOOSE_FOLDER,
                    onClick = onChooseFolder,
                )
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
        ThemeTextButton(
            text = ExportStrings.CANCEL,
            onClick = onCancel,
        )
        Spacer(modifier = Modifier.width(StudioDimens.m))
        PrimaryButton(
            text = ExportStrings.EXPORT,
            onClick = onExport,
            enabled = canExport,
        )
    }
}

@Composable
private fun LanguageCard(
    language: ExportLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectableCard(selected = selected, onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
        ) {
            Image(painter = languagePainter(language), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(language.displayName, style = MaterialTheme.typography.labelLarge)
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
