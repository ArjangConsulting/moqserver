package com.moqserver.studio.domain

import androidx.lifecycle.ViewModel
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectValidator
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.ValidationDiagnostic
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.suggestedVariantName
import com.moqserver.studio.projectformat.suggestedVariantReferenceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
class StudioRootViewModel(
    private val validator: ProjectValidator = ProjectValidator(),
) : ViewModel() {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private fun revalidate(project: MoqProject?): List<ValidationDiagnostic> {
        return if (project != null) validator.validate(project) else emptyList()
    }

    fun projectLoaded(project: MoqProject) {
        _state.update {
            it.copy(
                project = project,
                originalProject = project,
                isDirty = false,
                importState = null,
                statusLine = "Project loaded",
                selectedEndpointId = project.endpoints.firstOrNull()?.id,
                diagnostics = revalidate(project),
            )
        }
    }

    fun updateManifest(manifest: ProjectManifest) {
        val current = _state.value.project ?: return
        val updated = current.copy(manifest = manifest)
        _state.update { it.copy(project = updated, isDirty = true, diagnostics = revalidate(updated)) }
    }

    fun updateEndpoint(endpoint: EndpointDocument) {
        val current = _state.value.project ?: return
        val updated = current.copy(
            endpoints = current.endpoints.map { if (it.id == endpoint.id) endpoint else it }
        )
        _state.update { it.copy(project = updated, isDirty = true, diagnostics = revalidate(updated)) }
    }

    fun addEndpoint(endpoint: EndpointDocument) {
        val current = _state.value.project ?: return
        val updated = current.copy(endpoints = current.endpoints + endpoint)
        _state.update {
            it.copy(
                project = updated,
                isDirty = true,
                selectedEndpointId = endpoint.id,
                diagnostics = revalidate(updated),
            )
        }
    }

    fun removeEndpoint(endpointId: String) {
        val current = _state.value.project ?: return
        val updated = current.copy(endpoints = current.endpoints.filter { it.id != endpointId })
        _state.update {
            it.copy(
                project = updated,
                isDirty = true,
                selectedEndpointId = if (it.selectedEndpointId == endpointId) {
                    updated.endpoints.firstOrNull()?.id
                } else {
                    it.selectedEndpointId
                },
                diagnostics = revalidate(updated),
            )
        }
    }

    fun selectEndpoint(endpointId: String?) {
        _state.update { it.copy(selectedEndpointId = endpointId) }
    }

    fun projectSaved(path: String) {
        _state.update {
            val updated = it.project?.copy(projectPath = path)
            it.copy(
                project = updated,
                originalProject = updated,
                isDirty = false,
                statusLine = "All changes saved",
            )
        }
    }

    fun projectClosed() {
        _state.update {
            StudioState(
                statusLine = "Project closed. Open a .moqproj directory to get started.",
                recentProjects = it.recentProjects,
                ai = it.ai,
            )
        }
    }

    fun addRecentProject(path: String) {
        _state.update {
            val recent = (listOf(path) + it.recentProjects).distinct().take(10)
            it.copy(recentProjects = recent)
        }
    }

    fun setError(message: String) {
        _state.update { it.copy(statusLine = "Error: $message") }
    }

    fun setStatus(message: String) {
        _state.update { it.copy(statusLine = message) }
    }

    // -- AI --

    fun aiProvidersLoading() {
        _state.update { it.copy(ai = it.ai.copy(loading = true, error = null)) }
    }

    fun aiProvidersLoaded(providers: List<AIProviderInfo>) {
        val firstAvailable = providers.firstOrNull { it.available }?.id
        _state.update {
            val selectedProviderId = it.ai.selectedProviderId
            val preservedSelection = providers.firstOrNull { provider ->
                provider.id == selectedProviderId && provider.available
            }?.id
            it.copy(
                ai = it.ai.copy(
                    loading = false,
                    providers = providers,
                    selectedProviderId = preservedSelection ?: firstAvailable ?: providers.firstOrNull()?.id,
                    error = null,
                ),
            )
        }
    }

    fun aiProvidersLoadFailed(error: String) {
        _state.update { it.copy(ai = it.ai.copy(loading = false, error = error)) }
    }

    fun selectProvider(providerId: String) {
        _state.update { it.copy(ai = it.ai.copy(selectedProviderId = providerId)) }
    }

    fun aiActionStarted(action: AIAction) {
        _state.update {
            it.copy(aiAction = AIActionState(loading = true, action = action))
        }
    }

    fun aiActionFailed(error: String) {
        _state.update {
            it.copy(aiAction = it.aiAction.copy(loading = false, error = error))
        }
    }

    fun analyzeSpecCompleted(result: CompanionResponse<AnalyzeSpecResult>) {
        _state.update {
            it.copy(aiAction = AIActionState(action = AIAction.ANALYZE_SPEC, analyzeResult = result))
        }
    }

    fun generateVariantsCompleted(result: CompanionResponse<GenerateVariantsResult>) {
        _state.update {
            it.copy(aiAction = AIActionState(action = AIAction.GENERATE_VARIANTS, generateResult = result))
        }
    }

    fun refineProjectCompleted(result: CompanionResponse<RefineProjectResult>) {
        _state.update {
            it.copy(aiAction = AIActionState(action = AIAction.REFINE_PROJECT, refineResult = result))
        }
    }

    fun dismissAIAction() {
        _state.update { it.copy(aiAction = AIActionState()) }
    }

    fun setAiPanelVisible(visible: Boolean) {
        _state.update { it.copy(aiPanelVisible = visible) }
    }

    fun applyGeneratedVariant(variant: GeneratedVariant) {
        val current = _state.value.project ?: return
        // Find the endpoint matching the generated variant's key (e.g. "GET /pets")
        val parts = variant.endpointKey.split(" ", limit = 2)
        if (parts.size != 2) return
        val (method, path) = parts

        val endpoint = current.endpoints.find { it.method == method && it.path == path } ?: return
        val variantName = suggestedVariantName(
            status = variant.statusCode,
            existingNames = endpoint.variants.map(ProjectVariant::name),
            preferredName = variant.name,
        )
        val newVariant = ProjectVariant(
            name = variantName,
            referenceName = suggestedVariantReferenceName(
                preferredSource = variantName,
                status = variant.statusCode,
                existingNames = endpoint.variants.map(ProjectVariant::referenceName),
            ),
            status = variant.statusCode,
            headers = mapOf("Content-Type" to variant.contentType),
            body = YamlValue.from(variant.body),
        )
        val updated = endpoint.copy(variants = endpoint.variants + newVariant)
        updateEndpoint(updated)
    }

    // -- Import workflow --

    fun startImport(spec: ParsedSpec, source: ImportSourceType, fileName: String) {
        val entries = spec.endpoints.map { ImportEndpointEntry(endpoint = it, accepted = true) }
        val projectName = spec.title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifEmpty { "Imported API" }
        _state.update {
            it.copy(
                importState = ImportState(
                    source = source,
                    sourceFileName = fileName,
                    parsedSpec = spec,
                    entries = entries,
                    projectName = projectName,
                ),
                statusLine = "Import: ${spec.endpoints.size} endpoints found in $fileName",
            )
        }
    }

    fun toggleImportEndpoint(index: Int) {
        val importState = _state.value.importState ?: return
        val entries = importState.entries.toMutableList()
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(accepted = !entries[index].accepted)
        _state.update { it.copy(importState = importState.copy(entries = entries)) }
    }

    fun setAllImportEndpoints(accepted: Boolean) {
        val importState = _state.value.importState ?: return
        val entries = importState.entries.map { it.copy(accepted = accepted) }
        _state.update { it.copy(importState = importState.copy(entries = entries)) }
    }

    fun updateImportProjectName(name: String) {
        val importState = _state.value.importState ?: return
        _state.update { it.copy(importState = importState.copy(projectName = name)) }
    }

    fun confirmImport(projectPath: String): MoqProject? {
        val importState = _state.value.importState ?: return null
        val accepted = importState.entries.filter { it.accepted }.map { it.endpoint }
        if (accepted.isEmpty()) return null

        val project = ImportConverter.convert(
            spec = importState.parsedSpec,
            acceptedEndpoints = accepted,
            projectName = importState.projectName,
            projectPath = projectPath,
        )

        _state.update {
            it.copy(
                project = project,
                originalProject = project,
                isDirty = true, // New import needs saving
                importState = null,
                statusLine = "Imported ${accepted.size} endpoints from ${importState.sourceFileName}",
                selectedEndpointId = project.endpoints.firstOrNull()?.id,
                diagnostics = revalidate(project),
            )
        }

        return project
    }

    fun cancelImport() {
        _state.update { it.copy(importState = null, statusLine = "Import cancelled.") }
    }
}

data class StudioState(
    val project: MoqProject? = null,
    val originalProject: MoqProject? = null,
    val isDirty: Boolean = false,
    val statusLine: String = "No project loaded. Open a .moqproj directory to get started.",
    val selectedEndpointId: String? = null,
    val recentProjects: List<String> = emptyList(),
    val diagnostics: List<ValidationDiagnostic> = emptyList(),
    val importState: ImportState? = null,
    val ai: AIState = AIState(),
    val aiAction: AIActionState = AIActionState(),
    val aiPanelVisible: Boolean = false,
) {
    val isImporting: Boolean get() = importState != null
    val selectedEndpoint: EndpointDocument?
        get() = project?.endpoints?.find { it.id == selectedEndpointId }

    val hasErrors: Boolean
        get() = diagnostics.any { it.severity == ValidationDiagnostic.Severity.ERROR }

    val windowTitle: String
        get() {
            val name = project?.manifest?.name ?: "Moq Studio"
            return if (isDirty) "$name *" else name
        }
}
