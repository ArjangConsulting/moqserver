package com.moqserver.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.endpointdetail.*
import com.moqserver.studio.projectformat.*
import com.moqserver.studio.ui.*
import io.github.maniramezan.kmpcomponents.ConfirmDialog
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InfoTooltip
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.SegmentedContent
import io.github.maniramezan.kmpcomponents.SegmentedItem
import io.github.maniramezan.kmpcomponents.TabChip
import io.github.maniramezan.kmpcomponents.TabChipRow
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeTextButton

private object EndpointDetailStrings {
    const val DELETE_ENDPOINT_TITLE = "Delete endpoint?"
    const val DELETE = "Delete"
    const val CANCEL = "Cancel"
    const val METADATA = "Metadata"
    const val ALIAS_LABEL = "Alias (display name)"
    const val ALIAS_TOOLTIP = "Human-readable display name shown in the endpoint browser."
    const val REFERENCE_NAME = "Reference Name"
    const val REFERENCE_NAME_REQUIRED = "Reference name is required"
    const val REFERENCE_NAME_INVALID = "Use letters, numbers, and underscores only"
    const val REFERENCE_NAME_CONFLICT = "Reference name must be unique across APIs"
    const val REFERENCE_NAME_TOOLTIP = "Code-friendly unique name used by test cases to reference this API."
    const val DESCRIPTION_LABEL = "Description (optional)"
    const val DESCRIPTION_TOOLTIP = "Optional notes that give more context about what this API does."
    const val METHOD_LABEL = "Method"
    const val PATH_LABEL = "Path"
    const val PATH_TOOLTIP = "URL path this endpoint responds to (e.g. /users/{id})."
    const val VARIANTS_PREFIX = "Variants ("
    const val VARIANTS_SUFFIX = ")"
    const val VARIANT_DEFAULT_MARKER = " *"
    const val ADD_VARIANT = "+"
    const val DELETE_VARIANT_TITLE = "Delete variant?"
    const val DEFAULT_VARIANT_NAME = "Variant"
    const val THIS_VARIANT = "this variant"
    const val DELETE_DEFAULT_VARIANT_EDITED_MSG =
        " is the default variant and has been edited. Deleting it will assign another variant as default. This cannot be undone."
    const val DELETE_DEFAULT_VARIANT_MSG =
        " is the default variant. Deleting it will assign another variant as default."
    const val DELETE_EDITED_VARIANT_MSG = " has been edited. Deleting it cannot be undone."
}

@Composable
fun EndpointDetailPane(
    endpoint: EndpointDocument,
    originalEndpoint: EndpointDocument? = null,
    allEndpoints: List<EndpointDocument>,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    onDeleteEndpoint: () -> Unit = {},
    projectPath: String = "",
    variantReferenceSyncPreference: VariantReferenceSyncPreference? = null,
    aiAvailable: Boolean = false,
    aiProvider: AIProviderInfo? = null,
    aiBodyGenerating: Boolean = false,
    aiBodyError: String? = null,
    onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit = { _, _ -> },
    onGenerateBody: (ProjectVariant, String) -> Unit = { _, _ -> },
    readFixture: (String, String) -> String? = { _, _ -> null },
    initialVariantName: String? = null,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = EndpointDetailStrings.DELETE_ENDPOINT_TITLE,
            message = "Delete \"${endpoint.displayAlias}\" (${endpoint.method} ${endpoint.path})? This cannot be undone.",
            onConfirm = {
                showDeleteConfirm = false
                onDeleteEndpoint()
            },
            onDismiss = { showDeleteConfirm = false },
            confirmLabel = EndpointDetailStrings.DELETE,
            dismissLabel = EndpointDetailStrings.CANCEL,
            destructive = true,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StudioDimens.xxxl),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.xl),
    ) {
        EndpointHeader(endpoint = endpoint, onDelete = { showDeleteConfirm = true })
        HorizontalDivider()
        EndpointMetadataForm(endpoint = endpoint, allEndpoints = allEndpoints, onUpdateEndpoint = onUpdateEndpoint)
        HorizontalDivider()
        VariantSection(
            endpoint = endpoint,
            originalEndpoint = originalEndpoint,
            onUpdateEndpoint = onUpdateEndpoint,
            projectPath = projectPath,
            readFixture = readFixture,
            variantReferenceSyncPreference = variantReferenceSyncPreference,
            aiAvailable = aiAvailable,
            aiProvider = aiProvider,
            aiBodyGenerating = aiBodyGenerating,
            aiBodyError = aiBodyError,
            onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
            onGenerateBody = onGenerateBody,
            initialVariantName = initialVariantName,
        )
    }
}

@Composable
private fun EndpointHeader(
    endpoint: EndpointDocument,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioDimens.l),
    ) {
        MethodBadge(endpoint.method)
        Text(endpoint.path, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        ThemeTextButton(
            text = EndpointDetailStrings.DELETE,
            onClick = onDelete,
            destructive = true,
        )
    }

    Text(
        endpoint.displayAlias,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EndpointMetadataForm(
    endpoint: EndpointDocument,
    allEndpoints: List<EndpointDocument>,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
) {
    Text(EndpointDetailStrings.METADATA, style = MaterialTheme.typography.titleMedium)
    val siblingReferenceNames = allEndpoints
        .asSequence()
        .filter { it.id != endpoint.id }
        .map(EndpointDocument::referenceName)
        .toSet()
    val referenceNameBlank = endpoint.referenceName.isBlank()
    val referenceNameInvalid = endpoint.referenceName.isNotBlank() && !isValidReferenceName(endpoint.referenceName)
    val referenceNameConflict = endpoint.referenceName.isNotBlank() && endpoint.referenceName in siblingReferenceNames

    MethodAndPathRow(endpoint = endpoint, onUpdateEndpoint = onUpdateEndpoint)

    FormTextField(
        value = endpoint.alias ?: endpoint.displayAlias,
        onValueChange = {
            onUpdateEndpoint(endpoint.copy(alias = it.ifBlank { null }))
        },
        label = EndpointDetailStrings.ALIAS_LABEL,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = { InfoTooltip(EndpointDetailStrings.ALIAS_TOOLTIP) },
    )

    FormTextField(
        value = endpoint.referenceName,
        onValueChange = { onUpdateEndpoint(endpoint.copy(referenceName = it)) },
        label = EndpointDetailStrings.REFERENCE_NAME,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        errorText = when {
            referenceNameBlank -> EndpointDetailStrings.REFERENCE_NAME_REQUIRED
            referenceNameInvalid -> EndpointDetailStrings.REFERENCE_NAME_INVALID
            referenceNameConflict -> EndpointDetailStrings.REFERENCE_NAME_CONFLICT
            else -> null
        },
        trailingContent = { InfoTooltip(EndpointDetailStrings.REFERENCE_NAME_TOOLTIP) },
    )

    FormTextField(
        value = endpoint.description.orEmpty(),
        onValueChange = { onUpdateEndpoint(endpoint.copy(description = it.ifBlank { null })) },
        label = EndpointDetailStrings.DESCRIPTION_LABEL,
        modifier = Modifier.fillMaxWidth().heightIn(
            min = StudioDimens.multilineFieldMinHeight,
            max = StudioDimens.multilineFieldMaxHeight,
        ),
        trailingContent = { InfoTooltip(EndpointDetailStrings.DESCRIPTION_TOOLTIP) },
        singleLine = false,
    )
}

@Composable
private fun MethodAndPathRow(
    endpoint: EndpointDocument,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
        MenuPicker(
            items = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
            selected = endpoint.method,
            onSelect = { onUpdateEndpoint(endpoint.copy(method = it)) },
            label = EndpointDetailStrings.METHOD_LABEL,
            itemLabel = { it },
            modifier = Modifier.width(140.dp),
        )

        FormTextField(
            value = endpoint.path,
            onValueChange = { onUpdateEndpoint(endpoint.copy(path = it)) },
            label = EndpointDetailStrings.PATH_LABEL,
            singleLine = true,
            modifier = Modifier.weight(1f),
            trailingContent = { InfoTooltip(EndpointDetailStrings.PATH_TOOLTIP) },
        )
    }
}

@Composable
private fun VariantSection(
    endpoint: EndpointDocument,
    originalEndpoint: EndpointDocument? = null,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    projectPath: String = "",
    readFixture: (String, String) -> String? = { _, _ -> null },
    variantReferenceSyncPreference: VariantReferenceSyncPreference? = null,
    aiAvailable: Boolean = false,
    aiProvider: AIProviderInfo? = null,
    aiBodyGenerating: Boolean = false,
    aiBodyError: String? = null,
    onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit = { _, _ -> },
    onGenerateBody: (ProjectVariant, String) -> Unit = { _, _ -> },
    initialVariantName: String? = null,
) {
    var selectedVariantIndex by remember(endpoint.id, initialVariantName) {
        val index = if (initialVariantName != null) {
            endpoint.variants.indexOfFirst { it.name == initialVariantName }.coerceAtLeast(0)
        } else {
            0
        }
        mutableStateOf(index)
    }
    var selectedTab by remember(endpoint.id) { mutableStateOf(VariantDetailTab.SUMMARY) }
    val activeVariantIndex = selectedVariantIndex.coerceIn(0, endpoint.variants.lastIndex.coerceAtLeast(0))
    val requestRules = endpoint.requestRules ?: RequestRules()
    var variantIndexToDelete by remember { mutableStateOf<Int?>(null) }
    var variantDeleteIsDefault by remember { mutableStateOf(false) }
    var variantDeleteHasEdits by remember { mutableStateOf(false) }

    fun removeVariant(index: Int) {
        val updated = endpoint.removeVariantAt(index)
        onUpdateEndpoint(updated)
        selectedVariantIndex = (selectedVariantIndex.coerceAtMost(updated.variants.lastIndex)).coerceAtLeast(0)
    }

    fun requestRemove(index: Int) {
        val variant = endpoint.variants.getOrNull(index) ?: return
        val hasEdits = variant.hasSessionEdits(originalEndpoint?.variants.orEmpty())
        val isDefault = variant.isDefault == true
        if (hasEdits || isDefault) {
            variantDeleteIsDefault = isDefault
            variantDeleteHasEdits = hasEdits
            variantIndexToDelete = index
        } else {
            removeVariant(index)
        }
    }

    VariantSectionHeader(
        variantCount = endpoint.variants.size,
    )

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
                    preferredName = EndpointDetailStrings.DEFAULT_VARIANT_NAME,
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

    VariantDeleteConfirmDialog(
        variantIndexToDelete = variantIndexToDelete,
        endpoint = endpoint,
        isDefault = variantDeleteIsDefault,
        hasEdits = variantDeleteHasEdits,
        onConfirm = { deleteIndex ->
            removeVariant(deleteIndex)
            variantIndexToDelete = null
        },
        onDismiss = { variantIndexToDelete = null },
    )

    endpoint.variants.getOrNull(activeVariantIndex)?.let { variant ->
        VariantDetailCard(
            endpoint = endpoint,
            variant = variant,
            activeVariantIndex = activeVariantIndex,
            requestRules = requestRules,
            projectPath = projectPath,
            readFixture = readFixture,
            variantReferenceSyncPreference = variantReferenceSyncPreference,
            aiProvider = aiProvider,
            aiAvailable = aiAvailable,
            aiBodyGenerating = aiBodyGenerating,
            aiBodyError = aiBodyError,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onUpdateEndpoint = onUpdateEndpoint,
            onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
            onGenerateBody = { prompt -> onGenerateBody(variant, prompt) },
            onRequestRemove = { requestRemove(activeVariantIndex) },
        )
    }
}

@Composable
private fun VariantSectionHeader(
    variantCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${EndpointDetailStrings.VARIANTS_PREFIX}${variantCount}${EndpointDetailStrings.VARIANTS_SUFFIX}",
            style = MaterialTheme.typography.titleMedium,
        )
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
    TabChipRow {
        variants.forEachIndexed { index, variant ->
            val isSelected = index == selectedIndex
            val label = buildString {
                append(variant.name)
                if (variant.isDefault == true) append(EndpointDetailStrings.VARIANT_DEFAULT_MARKER)
            }
            TabChip(
                label = label,
                selected = isSelected,
                onClick = { onSelect(index) },
                closeDescription = "${EndpointDetailStrings.DELETE} ${variant.name}",
                onClose = if (variants.size > 1) { { onRemove(index) } } else null,
            )
        }
        TabChip(
            label = EndpointDetailStrings.ADD_VARIANT,
            selected = false,
            onClick = onAdd,
        )
    }
}

@Composable
private fun VariantDeleteConfirmDialog(
    variantIndexToDelete: Int?,
    endpoint: EndpointDocument,
    isDefault: Boolean,
    hasEdits: Boolean,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    variantIndexToDelete?.let { deleteIndex ->
        val variantName = endpoint.variants.getOrNull(deleteIndex)?.name ?: EndpointDetailStrings.THIS_VARIANT
        val message = "\"$variantName\"" + when {
            isDefault && hasEdits -> EndpointDetailStrings.DELETE_DEFAULT_VARIANT_EDITED_MSG
            isDefault -> EndpointDetailStrings.DELETE_DEFAULT_VARIANT_MSG
            else -> EndpointDetailStrings.DELETE_EDITED_VARIANT_MSG
        }
        ConfirmDialog(
            title = EndpointDetailStrings.DELETE_VARIANT_TITLE,
            message = message,
            onConfirm = { onConfirm(deleteIndex) },
            onDismiss = onDismiss,
            confirmLabel = EndpointDetailStrings.DELETE,
            dismissLabel = EndpointDetailStrings.CANCEL,
            destructive = true,
        )
    }
}

@Composable
private fun VariantDetailCard(
    endpoint: EndpointDocument,
    variant: ProjectVariant,
    activeVariantIndex: Int,
    requestRules: RequestRules,
    projectPath: String,
    readFixture: (String, String) -> String?,
    variantReferenceSyncPreference: VariantReferenceSyncPreference?,
    aiProvider: AIProviderInfo?,
    aiAvailable: Boolean,
    aiBodyGenerating: Boolean,
    aiBodyError: String?,
    selectedTab: VariantDetailTab,
    onSelectTab: (VariantDetailTab) -> Unit,
    onUpdateEndpoint: (EndpointDocument) -> Unit,
    onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit,
    onGenerateBody: (String) -> Unit,
    onRequestRemove: () -> Unit,
) {
    ThemeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(
            modifier = Modifier.padding(StudioDimens.xl),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.xl),
        ) {
            SegmentedContent(
                items = VariantDetailTab.entries.map { SegmentedItem(it.title) },
                selectedIndex = selectedTab.ordinal,
                onSelectionChanged = { onSelectTab(VariantDetailTab.entries[it]) },
            ) { index, _ ->
            when (VariantDetailTab.entries[index]) {
                VariantDetailTab.SUMMARY -> VariantSummaryTab(
                    endpoint = endpoint,
                    variant = variant,
                    projectPath = projectPath,
                    variantReferenceSyncPreference = variantReferenceSyncPreference,
                    onUpdate = { updated ->
                        onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                    },
                    onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
                    onRemove = onRequestRemove,
                    canRemove = endpoint.variants.size > 1,
                )

                VariantDetailTab.HEADERS -> HeadersTab(
                    headers = variant.headers ?: emptyMap(),
                    requestRules = requestRules,
                    onUpdate = { headers, updatedRules ->
                        onUpdateEndpoint(
                            endpoint.updateHeadersState(
                                index = activeVariantIndex,
                                variant = variant,
                                headers = headers,
                                requestRules = updatedRules,
                            ),
                        )
                    },
                )

                VariantDetailTab.COOKIES -> CookiesTab(
                    requestRules = requestRules,
                    onUpdate = { updatedRules ->
                        onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                    },
                )

                VariantDetailTab.BODY -> BodyTab(
					endpointMethod = endpoint.method,
					endpointPath = endpoint.path,
					variant = variant,
					aiProviderLabel = aiProvider?.displayName,
					canGenerateWithAi = aiAvailable,
					isGeneratingWithAi = aiBodyGenerating,
					generationError = aiBodyError,
                    onGenerateBody = onGenerateBody,
                    projectPath = projectPath,
                    readFixture = readFixture,
                    onUpdate = { updated ->
                        onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updated))
                    },
                )

                VariantDetailTab.CRITERIA -> CriteriaTab(
                    variant = variant,
                    requestRules = requestRules,
                    auth = endpoint.auth,
                    network = endpoint.network,
                    strictCallCount = endpoint.strictCallCount,
                    onUpdateVariant = { updatedVariant ->
                        onUpdateEndpoint(endpoint.updateVariant(activeVariantIndex, updatedVariant))
                    },
                    onUpdate = { updatedRules ->
                        onUpdateEndpoint(endpoint.copy(requestRules = updatedRules.normalize()))
                    },
                    onUpdateAuth = { onUpdateEndpoint(endpoint.copy(auth = it)) },
                    onUpdateNetwork = { onUpdateEndpoint(endpoint.copy(network = it)) },
                    onUpdateStrictCallCount = { onUpdateEndpoint(endpoint.copy(strictCallCount = it)) },
                )
            }
            }
        }
    }
}
