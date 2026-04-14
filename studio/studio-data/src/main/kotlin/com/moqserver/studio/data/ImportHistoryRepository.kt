package com.moqserver.studio.data

import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists per-project import history: the set of endpoint IDs the user previously deselected
 * during an "Update from Spec" import. On the next update import for the same project, these
 * IDs are pre-set to [accepted=false] so the user only has to review genuinely new or
 * newly changed endpoints.
 *
 * Data stored: opaque method+path slugs such as "get-users-param" — no sensitive information.
 *
 * Files are stored under ~/.moqserver/import-history/{safeProjectId}.json where the project ID
 * is derived from the project directory path (filesystem-safe slug). One file per project.
 */
class ImportHistoryRepository(
	private val historyDir: File = defaultHistoryDir(),
) {
	private val logger = loggerFor<ImportHistoryRepository>()
	private val json = Json {
		ignoreUnknownKeys = true
		prettyPrint = true
	}

	/**
	 * Loads the set of previously-deselected endpoint IDs for the given [projectPath].
	 * Returns an empty set if no history exists yet.
	 */
	fun loadDeselected(projectPath: String): Set<String> {
		val file = historyFileFor(projectPath)
		if (!file.exists()) return emptySet()
		return try {
			json.decodeFromString<ImportHistoryRecord>(file.readText())
				.deselectedEndpointIds
				.toSet()
		} catch (e: Exception) {
			logger.warn("Failed to load import history from ${file.path}: ${e.message}. Using defaults.")
			emptySet()
		}
	}

	/**
	 * Persists the [deselectedEndpointIds] for the given [projectPath].
	 * If the set is empty the file is deleted to avoid accumulating empty files.
	 */
	fun saveDeselected(projectPath: String, deselectedEndpointIds: Set<String>) {
		val file = historyFileFor(projectPath)
		if (deselectedEndpointIds.isEmpty()) {
			if (file.exists()) file.delete()
			return
		}
		try {
			file.parentFile?.mkdirs()
			file.writeText(json.encodeToString(ImportHistoryRecord(deselectedEndpointIds.sorted())))
			logger.info("Saved import history (${deselectedEndpointIds.size} deselected) to ${file.path}")
		} catch (e: Exception) {
			logger.warn("Failed to save import history to ${file.path}: ${e.message}")
		}
	}

	private fun historyFileFor(projectPath: String): File {
		val safeId = projectPath
			.replace(Regex("[^a-zA-Z0-9._-]"), "_")
			.trimStart('_')
			.take(200)
			.ifEmpty { "default" }
		return File(historyDir, "$safeId.json")
	}

	@Serializable
	private data class ImportHistoryRecord(
		val deselectedEndpointIds: List<String> = emptyList(),
	)

	companion object {
		private const val SETTINGS_DIR = ".moqserver"
		private const val HISTORY_SUBDIR = "import-history"

		fun defaultHistoryDir(): File {
			val home = System.getProperty("user.home")
			return File(home, "$SETTINGS_DIR/$HISTORY_SUBDIR")
		}
	}
}
