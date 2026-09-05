package com.moqserver.studio.projectformat

import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatServiceException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val operations = Mutex()
    private var baselinePath: String? = null
    private var baselineRevision: String? = null

    private suspend fun handle(): String {
        sessionHandle?.let { return it }
        val handle = client.openSession()
        sessionHandle = handle
        return handle
    }

    suspend fun load(projectPath: String): MoqProject = operations.withLock {
        val fresh = client.openSession()
        try {
            val description = client.openProject(fresh, projectPath)
            val project = client.readProject(fresh)
            val previous = sessionHandle
            sessionHandle = fresh
            if (previous != null) runCatching { client.closeSession(previous) }
            baselinePath = description.path
            baselineRevision = description.revision
            project
        } catch (error: Exception) {
            runCatching { client.closeSession(fresh) }
            throw error
        }
    }

    suspend fun save(project: MoqProject, path: String) = operations.withLock {
        val target = project.copy(projectPath = path)
        val sameDestination = baselinePath?.let { File(it).canonicalPath == File(path).canonicalPath } ?: false
        val expectedRevision = if (sameDestination) baselineRevision else null
        val description = try {
            client.writeProject(handle(), target, expectedRevision = expectedRevision)
        } catch (error: FormatServiceException) {
            if (error.code != "E_UNKNOWN_SESSION") throw error
            val restored = restoreSession(verifyRevision = sameDestination)
            try {
                client.writeProject(
                    restored,
                    target,
                    expectedRevision = expectedRevision,
                ).also { sessionHandle = restored }
            } catch (failure: Exception) {
                runCatching { client.closeSession(restored) }
                throw failure
            }
        }
        baselinePath = description.path
        baselineRevision = description.revision
        logger.info("Project saved: {}", path)
    }

    private suspend fun restoreSession(verifyRevision: Boolean): String {
        val fresh = client.openSession()
        try {
            baselinePath?.let { path ->
                val restored = client.openProject(fresh, path)
                if (verifyRevision && (baselineRevision == null || restored.revision != baselineRevision)) {
                    throw FormatServiceException(
                        "E_PROJECT_CHANGED",
                        "The project changed on disk. Save As to preserve your edits, or reload the project.",
                    )
                }
            }
            return fresh
        } catch (error: Exception) {
            runCatching { client.closeSession(fresh) }
            throw error
        }
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
        val fixturesPath = rootPath.resolve(MoqProjectFormat.FIXTURES_DIR)
        return if (filePath.startsWith(fixturesPath)) file else null
    }
}
