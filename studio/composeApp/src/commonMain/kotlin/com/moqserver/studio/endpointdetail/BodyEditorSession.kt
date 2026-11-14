package com.moqserver.studio.endpointdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arjangconsulting.codeeditor.CodeEditorState
import com.arjangconsulting.codeeditor.FormatResult
import com.arjangconsulting.codeeditor.FormattingOptions
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.format
import com.moqserver.studio.projectformat.ProjectVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

internal class BodyEditorSession(private val source: String) {
	val state = CodeEditorState(source)
	private var formatJob: Job? = null
	var isEditing by mutableStateOf(false)
		private set
	var validationError by mutableStateOf<String?>(null)
		private set

	fun start() {
		dispose()
		state.resetText(source)
		validationError = null
		isEditing = true
	}

	fun cancel() {
		dispose()
		state.resetText(source)
		validationError = null
		isEditing = false
	}

	fun clearValidationError() {
		validationError = null
	}

	fun validate() {
		validationError = validateJsonBodyText(state.text)
	}

	fun save(variant: ProjectVariant, isJson: Boolean): ProjectVariant? {
		val parsed = if (isJson) {
			parseJsonBodyText(state.text)
		} else {
			Result.success(parseEditableText(state.text, variant.body))
		}
		validationError = parsed.exceptionOrNull()?.let(::invalidJsonMessage)
		return parsed.getOrNull()?.let { body ->
			dispose()
			isEditing = false
			if (state.text == source) variant else variant.copy(body = body, bodyFile = null)
		}
	}

	fun dispose() {
		formatJob?.cancel()
		formatJob = null
	}

	suspend fun format(service: LanguageService): FormatResult {
		dispose()
		val job = currentCoroutineContext().job
		formatJob = job
		return try {
			state.format(service, FormattingOptions(tabSize = 2))
		} finally {
			if (formatJob === job) formatJob = null
		}
	}
}
