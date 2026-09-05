package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.MoqProject

internal fun StudioState.afterProjectSaved(savedProject: MoqProject, path: String, generation: Long): StudioState {
	if (projectGeneration != generation) return this
	val current = project ?: return this
	if (current.projectPath != savedProject.projectPath) return this
	val updated = current.copy(projectPath = path)
	val persisted = savedProject.copy(projectPath = path)
	val dirty = updated != persisted
	return copy(
		project = updated,
		originalProject = persisted,
		isDirty = dirty,
		statusLine = if (dirty) "Saved; newer changes remain unsaved" else "All changes saved",
		transientDiagnostic = null,
	)
}
