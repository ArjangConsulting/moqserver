package com.moqserver.studio.data

import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class RecentProjectsRepository(
    private val settingsFile: File = defaultSettingsFile(),
) {
    private val logger = loggerFor<RecentProjectsRepository>()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(): List<String> {
        if (!settingsFile.exists()) {
            return emptyList()
        }

        return try {
            json.decodeFromString<RecentProjectsSettings>(settingsFile.readText()).projects
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_RECENT_PROJECTS)
        } catch (e: Exception) {
            logger.warn("Failed to load recent projects from ${settingsFile.path}: ${e.message}. Using defaults.")
            emptyList()
        }
    }

    fun save(projects: List<String>) {
        val sanitizedProjects = projects
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_RECENT_PROJECTS)

        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(json.encodeToString(RecentProjectsSettings(sanitizedProjects)))
        logger.info("Saved ${sanitizedProjects.size} recent project(s) to ${settingsFile.path}")
    }

    @Serializable
    private data class RecentProjectsSettings(
        val projects: List<String> = emptyList(),
    )

    companion object {
        private const val SETTINGS_DIR = ".moqserver"
        private const val SETTINGS_FILENAME = "studio-recent-projects.json"
        private const val MAX_RECENT_PROJECTS = 10

        fun defaultSettingsFile(): File {
            val home = System.getProperty("user.home")
            return File(home, "$SETTINGS_DIR/$SETTINGS_FILENAME")
        }
    }
}
