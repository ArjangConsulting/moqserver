package com.moqserver.studio.projectformat

import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.format.FormatClient
import java.io.File

/**
 * Loads and saves `.moqproj` bundles through `moq-format` rather than a local Kotlin codec — see
 * `format.FormatClient` / `format.RemoteProjectValidator` for the other half of this cutover.
 * [readFixture] is the one exception: reading a fixture's bytes to show in the UI is plain,
 * path-guarded file I/O with no format semantics involved, so it stays local rather than round
 * tripping through the service for no reason.
 *
 * Holds one `moq-format` session for its lifetime, opened lazily on first use. Reusing the same
 * session across a load and later saves is what lets `ProjectStore`'s on-disk-changed guard
 * actually protect against a concurrent external edit — see the design note on
 * `MoqService.writeProject` for why re-opening fresh on every call would defeat it.
 */
class ProjectRepository(private val client: FormatClient) {
    private val logger = loggerFor<ProjectRepository>()
    private var sessionHandle: String? = null

    private suspend fun handle(): String {
        sessionHandle?.let { return it }
        val handle = client.openSession()
        sessionHandle = handle
        return handle
    }

    suspend fun load(projectPath: String): MoqProject {
        logger.info("Loading project from {}", projectPath)
        val handle = handle()
        client.openProject(handle, projectPath, force = false)
        val project = client.readProject(handle)
        logger.info("Project loaded: {} with {} endpoint(s)", project.manifest.name, project.endpoints.size)
        return project
    }

    /**
     * Persists [project] to [path]. [path] and `project.projectPath` may differ — Save As passes
     * a new [path] while [project] still carries wherever it was last loaded from or saved to.
     */
    suspend fun save(project: MoqProject, path: String) {
        logger.info("Saving project {} to {}", project.manifest.name, path)
        val handle = handle()
        val target = if (project.projectPath == path) project else project.copy(projectPath = path)
        client.writeProject(handle, target, force = false)
        logger.info("Project saved: {} with {} endpoint(s)", target.manifest.name, target.endpoints.size)
    }

    /**
     * Reads a fixture's raw text content, or null if it doesn't exist or [bodyFile] doesn't
     * resolve to a path inside [projectPath]'s `fixtures/` directory. Local file I/O — see the
     * type doc for why this one call doesn't go through `moq-format`.
     */
    fun readFixture(projectPath: String, bodyFile: String): String? {
        val file = resolveContainedFixture(File(projectPath), bodyFile)
        if (file == null) {
            logger.warn("Refusing to read fixture path outside the project bundle: {}", bodyFile)
            return null
        }
        return if (file.isFile) file.readText() else null
    }

    /**
     * Resolves [relativePath] against [root], returning null unless the result stays inside
     * the project's fixtures directory. Guards against `..`/absolute body_file values from
     * untrusted .moqproj bundles escaping the bundle on read.
     */
    private fun resolveContainedFixture(root: File, relativePath: String): File? {
        if (!relativePath.startsWith("${MoqProjectFormat.FIXTURES_DIR}/")) return null
        val file = File(root, relativePath)
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) file else null
    }
}
