package com.moqserver.studio.domain

import androidx.lifecycle.ViewModel
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.ValidationDiagnostic
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.suggestedVariantName
import com.moqserver.studio.projectformat.suggestedVariantReferenceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val historyDebounceDuration = 500.milliseconds
private data class HistoryEntry(
    val project: MoqProject,
    val selectedEndpointId: String?,
)

/**
 * @param validate Runs full project validation. Defaults to producing no diagnostics — the
 *   production composition root (`Main.kt`, JVM-only) supplies the real implementation backed by
 *   `format.RemoteProjectValidator`, since that type lives in `studio-project-format`'s jvmMain
 *   and can't be referenced from this commonMain class directly. Tests get the harmless default
 *   for free rather than needing a live `moq-format` process.
 */
class StudioRootViewModel(
    private val validate: suspend (MoqProject) -> List<ValidationDiagnostic> = { emptyList() },
) : ViewModel() {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var lastContinuousEditMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var validationJob: Job? = null

    // Deliberately not androidx.lifecycle's `viewModelScope`: it launches on `Dispatchers.Main`,
    // which requires a Main-dispatcher implementation (coroutines-swing in the packaged desktop
    // app) to even be present on the classpath. This module's unit tests don't pull that in and
    // shouldn't have to just to construct a ViewModel — `_state` is a `StateFlow`, safe to update
    // from any dispatcher, and Compose's `collectAsState()` handles the cross-thread hop itself.
    private val validationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCleared() {
        validationScope.cancel()
    }

    private fun resetContinuousHistoryWindow() {
        lastContinuousEditMark = null
    }

    /**
     * Snapshots current state onto the undo stack.
     *
     * [isContinuousEdit] should be true for field-level edits (text, status, delay) where
     * rapid keystrokes should coalesce into a single undo step. Structural operations
     * (add/remove endpoint) pass false so they are always recorded immediately.
     */
    private fun recordHistory(isContinuousEdit: Boolean = false) {
        val s = _state.value
        val project = s.project ?: return
        val entry = HistoryEntry(project, s.selectedEndpointId)
        val now = TimeSource.Monotonic.markNow()
        val lastMark = lastContinuousEditMark
        if (isContinuousEdit && lastMark != null && (now - lastMark) < historyDebounceDuration) {
            lastContinuousEditMark = now
            return
        }
        if (undoStack.lastOrNull() == entry) {
            redoStack.clear()
            _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = false) }
            lastContinuousEditMark = if (isContinuousEdit) now else null
            return
        }
        if (undoStack.size >= 100) undoStack.removeFirst()
        undoStack.addLast(entry)
        redoStack.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
        lastContinuousEditMark = if (isContinuousEdit) now else null
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        resetContinuousHistoryWindow()
        val s = _state.value
        val current = s.project ?: return
        redoStack.addLast(HistoryEntry(current, s.selectedEndpointId))
        val restored = entry.project
        _state.update {
            it.copy(
                project = restored,
                selectedEndpointId = entry.selectedEndpointId,
                isDirty = restored != it.originalProject,
                transientDiagnostic = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
            )
        }
        scheduleRevalidate(restored)
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        resetContinuousHistoryWindow()
        val s = _state.value
        val current = s.project ?: return
        undoStack.addLast(HistoryEntry(current, s.selectedEndpointId))
        val restored = entry.project
        _state.update {
            it.copy(
                project = restored,
                selectedEndpointId = entry.selectedEndpointId,
                isDirty = restored != it.originalProject,
                transientDiagnostic = null,
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
            )
        }
        scheduleRevalidate(restored)
    }

    private fun duplicateDefaultVariantDiagnostic(
        existing: EndpointDocument,
        updated: EndpointDocument,
    ): ValidationDiagnostic? {
        val existingDefaultCount = existing.variants.count { it.isDefault == true }
        val updatedDefaultCount = updated.variants.count { it.isDefault == true }
        if (existingDefaultCount != 1 || updatedDefaultCount <= 1 || updatedDefaultCount <= existingDefaultCount) {
            return null
        }

        val currentDefault = existing.variants.firstOrNull { it.isDefault == true } ?: return null
        val newlyMarkedDefault = updated.variants
            .withIndex()
            .firstOrNull { (index, variant) ->
                variant.isDefault == true && existing.variants.getOrNull(index)?.isDefault != true
            }
        val newlyMarkedDefaultVariant = newlyMarkedDefault?.value
        val message = if (newlyMarkedDefaultVariant != null) {
            "\"${currentDefault.name}\" is already the default variant. " +
                "Clear it before marking \"${newlyMarkedDefaultVariant.name}\" as default."
        } else {
            "Only one variant may be marked as default."
        }

        return ValidationDiagnostic(
            severity = ValidationDiagnostic.Severity.ERROR,
            message = message,
            endpointId = existing.id,
            endpointLabel = "${updated.method} ${updated.path}",
            variantName = newlyMarkedDefaultVariant?.name,
            field = newlyMarkedDefault?.let { (index, _) -> "variants[$index].default" },
        )
    }

    /**
     * Revalidation is asynchronous — `moq-format` is a subprocess round trip, not a local
     * function call — so callers apply their own state change synchronously (project, dirty,
     * etc.) and then call this to schedule diagnostics arriving later. A superseded validation
     * (the user typed again before the previous one returned) is cancelled outright rather than
     * left to race: `_state.update` also checks `it.project == project` so a slow response that
     * wins the cancellation race still can't overwrite newer diagnostics.
     */
    private fun scheduleRevalidate(project: MoqProject?) {
        validationJob?.cancel()
        if (project == null) {
            _state.update { it.copy(diagnostics = emptyList()) }
            return
        }
        validationJob = validationScope.launch {
            val diagnostics = try {
                validate(project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(
                    ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Validation is unavailable: ${e.message ?: e::class.simpleName}",
                    ),
                )
            }
            _state.update { if (it.project == project) it.copy(diagnostics = diagnostics) else it }
        }
    }

    private var projectGeneration = 0L

    fun projectLoaded(project: MoqProject) {
        projectGeneration += 1
        resetContinuousHistoryWindow()
        undoStack.clear()
        redoStack.clear()
        _state.update {
            it.copy(
                project = project,
                projectGeneration = projectGeneration,
                originalProject = project,
                isDirty = false,
                importState = null,
                statusLine = "Project loaded",
                transientDiagnostic = null,
                selectedEndpointId = project.endpoints.firstOrNull()?.id,
                canUndo = false,
                canRedo = false,
            )
        }
        scheduleRevalidate(project)
    }

    fun updateManifest(manifest: ProjectManifest) {
        val current = _state.value.project ?: return
        if (current.manifest == manifest) return
        recordHistory(isContinuousEdit = true)
        val updated = current.copy(manifest = manifest)
        _state.update {
            it.copy(
                project = updated,
                isDirty = true,
                transientDiagnostic = null,
            )
        }
        scheduleRevalidate(updated)
    }

    fun updateEndpoint(endpoint: EndpointDocument) {
        val current = _state.value.project ?: return
        val existing = current.endpoints.find { it.id == endpoint.id } ?: return
        if (existing == endpoint) return
        val duplicateDefaultVariantDiagnostic = duplicateDefaultVariantDiagnostic(existing, endpoint)
        if (duplicateDefaultVariantDiagnostic != null) {
            _state.update {
                it.copy(
                    statusLine = "Error: ${duplicateDefaultVariantDiagnostic.message}",
                    transientDiagnostic = duplicateDefaultVariantDiagnostic,
                )
            }
            return
        }
        recordHistory(isContinuousEdit = true)
        val updated = current.copy(
            endpoints = current.endpoints.map { if (it.id == endpoint.id) endpoint else it },
        )
        _state.update {
            it.copy(
                project = updated,
                isDirty = true,
                transientDiagnostic = null,
            )
        }
        scheduleRevalidate(updated)
    }

    fun addEndpoint(endpoint: EndpointDocument) {
        val current = _state.value.project ?: return
        recordHistory()
        val updated = current.copy(endpoints = current.endpoints + endpoint)
        _state.update {
            it.copy(
                project = updated,
                isDirty = true,
                transientDiagnostic = null,
                selectedEndpointId = endpoint.id,
            )
        }
        scheduleRevalidate(updated)
    }

    fun removeEndpoint(endpointId: String) {
        val current = _state.value.project ?: return
        recordHistory()
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
                transientDiagnostic = null,
            )
        }
        scheduleRevalidate(updated)
    }

    fun selectEndpoint(endpointId: String?, variantName: String? = null) {
        _state.update { it.copy(selectedEndpointId = endpointId, pendingVariantName = variantName) }
    }

    fun runtimeUpdated(runtime: RuntimeInspectionState) {
        _state.update { it.copy(runtime = runtime) }
    }

    fun projectSaved(savedProject: MoqProject, path: String, generation: Long = state.value.projectGeneration) {
        _state.update { it.afterProjectSaved(savedProject, path, generation) }
    }

    fun projectClosed() {
        resetContinuousHistoryWindow()
        undoStack.clear()
        redoStack.clear()
        validationJob?.cancel()
        _state.update {
            StudioState(
                statusLine = "Project closed. Open a .moqproj directory to get started.",
                recentProjects = it.recentProjects,
                ai = it.ai,
                runtime = it.runtime,
            )
        }
    }

    fun addRecentProject(path: String) {
        _state.update {
            val recent = (listOf(path) + it.recentProjects).distinct().take(10)
            it.copy(recentProjects = recent)
        }
    }

    fun setRecentProjects(paths: List<String>) {
        _state.update { it.copy(recentProjects = paths.distinct().take(10)) }
    }

    fun removeRecentProject(path: String) {
        _state.update { it.copy(recentProjects = it.recentProjects.filterNot { recentPath -> recentPath == path }) }
    }

    fun setError(message: String) {
        _state.update {
            it.copy(
                statusLine = "Error: $message",
                transientDiagnostic = ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = message,
                ),
            )
        }
    }

    fun setStatus(message: String) {
        _state.update { it.copy(statusLine = message, transientDiagnostic = null) }
    }

    fun dismissError() {
        _state.update { it.copy(transientDiagnostic = null) }
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
                provider.id == selectedProviderId
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
		val entries = spec.endpoints.map(ImportEntryFactory::forNewImport)
        val projectName = spec.title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifEmpty { "Imported API" }
        _state.update {
            it.copy(
                importState = ImportState(
                    source = source,
                    sourceFileName = fileName,
                    parsedSpec = spec,
                    entries = entries,
                    projectName = projectName,
                    mode = ImportMode.NewProject,
                    updateSelection = UpdateSelection(),
                ),
                statusLine = "Import: ${spec.endpoints.size} endpoints found in $fileName",
            )
        }
    }

    /**
     * Starts an update-from-spec import against the currently loaded project.
     *
     * Each parsed endpoint is classified as NEW, CHANGED, or UNCHANGED relative to the project:
     * - NEW endpoints are pre-selected ([accepted=true]).
     * - CHANGED endpoints are pre-selected ([accepted=true]).
     * - UNCHANGED endpoints are pre-deselected ([accepted=false]).
     *
     * [previouslyDeselectedIds] is the set of endpoint IDs the user deselected in a prior update
     * import for this project. Any NEW or CHANGED endpoint whose ID is in this set is also
     * pre-deselected, so users don't have to re-dismiss the same endpoints repeatedly.
     */
    fun startUpdateFromSpec(
        spec: ParsedSpec,
        source: ImportSourceType,
        fileName: String,
        previouslyDeselectedIds: Set<String> = emptySet(),
    ) {
        val currentProject = _state.value.project ?: return
        val existingById = currentProject.endpoints.associateBy { it.id }

		val entries = spec.endpoints.map { parsed ->
			ImportEntryFactory.forUpdateImport(
				parsed = parsed,
				existing = existingById[ImportConverter.endpointId(parsed.method, parsed.path)],
				previouslyDeselectedIds = previouslyDeselectedIds,
			)
		}

        val newCount = entries.count { it.updateStatus == EndpointUpdateStatus.NEW }
        val changedCount = entries.count { it.updateStatus == EndpointUpdateStatus.CHANGED }
        val unchangedCount = entries.count { it.updateStatus == EndpointUpdateStatus.UNCHANGED }

        _state.update {
            it.copy(
                importState = ImportState(
                    source = source,
                    sourceFileName = fileName,
                    parsedSpec = spec,
                    entries = entries,
                    projectName = currentProject.manifest.name,
                    mode = ImportMode.UpdateExisting(currentProject),
                    updateSelection = UpdateSelection(),
                ),
                statusLine = "Update: $newCount new, $changedCount changed, $unchangedCount unchanged endpoint(s) in $fileName",
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

    fun importAIGenerationStarted(index: Int) {
        val importState = _state.value.importState ?: return
        val entries = importState.entries.toMutableList()
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(aiGenerationLoading = true, aiGenerationError = null)
        _state.update {
            it.copy(
                importState = importState.copy(entries = entries),
                statusLine = "Generating AI mock variants for ${entries[index].endpoint.method} ${entries[index].endpoint.path}",
            )
        }
    }

    fun importAIGenerationCompleted(index: Int, generatedResponses: List<ParsedResponse>) {
        val importState = _state.value.importState ?: return
		val existingVariants = existingImportVariants(importState, index)
		val result = completeImportAIGeneration(importState, index, generatedResponses, existingVariants) ?: return
        _state.update {
            it.copy(
				importState = importState.copy(entries = result.entries),
				statusLine = result.statusLine,
            )
        }
    }

    fun importAIGenerationFailed(index: Int, error: String) {
        val importState = _state.value.importState ?: return
		val result = failImportAIGeneration(importState, index, error) ?: return
        _state.update {
            it.copy(
				importState = importState.copy(entries = result.entries),
				statusLine = result.statusLine,
            )
        }
    }

    fun importAIBulkStarted(totalCount: Int) {
        val importState = _state.value.importState ?: return
        _state.update {
            it.copy(
                importState = importState.copy(
                    aiBulkState = ImportAIBulkState(
                        running = true,
                        completedCount = 0,
                        totalCount = totalCount,
                    ),
                ),
                statusLine = "Generating AI mock variants for $totalCount endpoint(s)",
            )
        }
    }

    fun importAIBulkProgress(completedCount: Int) {
        val importState = _state.value.importState ?: return
        val bulkState = importState.aiBulkState
        _state.update {
            val progress = completedCount.coerceAtMost(bulkState.totalCount)
            it.copy(
                importState = importState.copy(
                    aiBulkState = bulkState.copy(
                        running = true,
                        completedCount = progress,
                    ),
                ),
                statusLine = "Generating AI mock variants $progress/${bulkState.totalCount}",
            )
        }
    }

    fun importAIBulkFinished() {
        val importState = _state.value.importState ?: return
        val generatedEndpoints = importState.entries.count { it.generatedResponses.isNotEmpty() }
        _state.update {
            it.copy(
                importState = importState.copy(
                    aiBulkState = importState.aiBulkState.copy(
                        running = false,
                        completedCount = importState.aiBulkState.totalCount,
                    ),
                ),
                statusLine = "AI mock generation finished for $generatedEndpoints endpoint(s)",
            )
        }
    }

    fun updateImportProjectName(name: String) {
        val importState = _state.value.importState ?: return
        _state.update { it.copy(importState = importState.copy(projectName = name)) }
    }

	fun updateImportAIContextHint(index: Int, hint: String) {
		val importState = _state.value.importState ?: return
		val entries = updateImportAIContextHintEntry(importState, index, hint) ?: return
		_state.update { it.copy(importState = importState.copy(entries = entries)) }
	}

	fun updateImportResponseName(index: Int, responseIndex: Int, name: String) {
		val importState = _state.value.importState ?: return
		val entries = renameImportedResponse(
			importState = importState,
			index = index,
			responseIndex = responseIndex,
			name = name,
			existingVariants = existingImportVariants(importState, index),
		) ?: return
		_state.update { it.copy(importState = importState.copy(entries = entries)) }
	}

	fun updateGeneratedImportResponseName(index: Int, generatedResponseIndex: Int, name: String) {
		val importState = _state.value.importState ?: return
		val entries = renameGeneratedResponse(
			importState = importState,
			index = index,
			generatedResponseIndex = generatedResponseIndex,
			name = name,
			existingVariants = existingImportVariants(importState, index),
		) ?: return
		_state.update { it.copy(importState = importState.copy(entries = entries)) }
	}

	fun toggleGeneratedImportResponse(index: Int, generatedResponseIndex: Int) {
		val importState = _state.value.importState ?: return
		val entries = toggleGeneratedImportResponseSelection(importState, index, generatedResponseIndex) ?: return
		_state.update { it.copy(importState = importState.copy(entries = entries)) }
	}

	fun updateImportSelection(selection: UpdateSelection) {
		val importState = _state.value.importState ?: return
		_state.update { it.copy(importState = importState.copy(updateSelection = selection)) }
	}

    fun confirmImport(projectPath: String): MoqProject? {
        val importState = _state.value.importState ?: return null
        val originalProject = _state.value.originalProject
        val acceptedEntries = importState.entries.filter {
			it.isEffectivelyAccepted(importState.updateSelection, importState.isUpdateMode)
		}
        if (acceptedEntries.isEmpty()) return null

        val project = when (val mode = importState.mode) {
            is ImportMode.NewProject -> ImportConverter.convert(
                spec = importState.parsedSpec,
                acceptedEntries = acceptedEntries,
                projectName = importState.projectName,
                projectPath = projectPath,
            )
            is ImportMode.UpdateExisting -> ImportConverter.merge(
                existingProject = mode.existingProject.copy(projectPath = mode.existingProject.projectPath),
                acceptedEntries = acceptedEntries,
				updateSelection = importState.updateSelection,
            )
        }

        _state.update {
            it.copy(
                project = project,
                originalProject = originalProject,
                isDirty = true, // New import needs saving
                importState = null,
                statusLine = "Imported ${acceptedEntries.size} endpoints from ${importState.sourceFileName}",
                transientDiagnostic = null,
                selectedEndpointId = project.endpoints.firstOrNull()?.id,
            )
        }
        scheduleRevalidate(project)

        return project
    }

    /**
     * Returns the set of endpoint IDs that were deselected in the current import review.
     * Only meaningful for update-mode imports; returns an empty set for new-project imports.
     * Should be called just before [confirmImport] or [cancelImport] to persist history.
     */
    fun collectDeselectedImportIds(): Set<String> {
        val importState = _state.value.importState ?: return emptySet()
        if (importState.mode !is ImportMode.UpdateExisting) return emptySet()
        return importState.entries
            .filter { !it.accepted && it.updateStatus != EndpointUpdateStatus.UNCHANGED }
            .map { ImportConverter.endpointId(it.endpoint.method, it.endpoint.path) }
            .toSet()
    }

    fun cancelImport() {
        _state.update { it.copy(importState = null, statusLine = "Import cancelled.") }
    }

	private fun existingImportVariants(importState: ImportState, index: Int): List<ProjectVariant> {
		val endpoint = importState.entries.getOrNull(index)?.endpoint ?: return emptyList()
		val existingProject = (importState.mode as? ImportMode.UpdateExisting)?.existingProject ?: return emptyList()
		val endpointId = ImportConverter.endpointId(endpoint.method, endpoint.path)
		return existingProject.endpoints.firstOrNull { it.id == endpointId }?.variants.orEmpty()
	}
}

data class StudioState(
    val projectGeneration: Long = 0,
    val runtime: RuntimeInspectionState = RuntimeInspectionState(),
    val project: MoqProject? = null,
    val originalProject: MoqProject? = null,
    val isDirty: Boolean = false,
    val statusLine: String = "No project loaded. Open a .moqproj directory to get started.",
    val transientDiagnostic: ValidationDiagnostic? = null,
    val selectedEndpointId: String? = null,
    val pendingVariantName: String? = null,
    val recentProjects: List<String> = emptyList(),
    val diagnostics: List<ValidationDiagnostic> = emptyList(),
    val importState: ImportState? = null,
    val ai: AIState = AIState(),
    val aiAction: AIActionState = AIActionState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val isImporting: Boolean get() = importState != null
    val selectedEndpoint: EndpointDocument?
        get() = project?.endpoints?.find { it.id == selectedEndpointId }

    val hasErrors: Boolean
        get() = diagnostics.any { it.severity == ValidationDiagnostic.Severity.ERROR }

    val windowTitle: String
        get() {
            val name = project?.manifest?.name ?: "moqserver Studio"
            return if (isDirty) "$name *" else name
        }
}
