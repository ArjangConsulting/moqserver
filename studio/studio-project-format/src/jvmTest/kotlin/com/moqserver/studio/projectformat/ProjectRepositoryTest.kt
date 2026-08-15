package com.moqserver.studio.projectformat

import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import com.moqserver.studio.projectformat.format.FormatServiceException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ProjectRepository` is now a thin client over `moq-format` — the format's own load/write
 * correctness (YAML encoding edge cases, header quoting, multiline bodies, GraphQL, cookies,
 * request-match rules, endpoint-id safety, ...) lives in and is tested by the Swift core
 * (`ProjectWriterTests`/`ProjectLoaderTests`/`WriteProjectTests`). Re-asserting all of that here
 * through IPC would test Swift a second time, more slowly, from a different client — this file's
 * job is narrower: does the Kotlin client correctly drive the real process through the
 * load/save/readFixture calls Studio actually makes.
 *
 * Requires a built moq-format binary (`MOQSERVER_FORMAT_BINARY`, or on PATH); skips gracefully
 * when none is found, matching `FormatClientIntegrationTest`.
 */
class ProjectRepositoryTest {
    private val sampleProjectPath = findSampleProject()
    private var process: FormatProcess? = null
    private var repo: ProjectRepository? = null

    private fun findSampleProject(): String {
        var current: File? = File(System.getProperty("user.dir")).canonicalFile
        while (current != null) {
            val sample = File(current, "server/Tests/MoqFormatTests/Fixtures/sample-app.moqproj")
            if (sample.isDirectory) {
                return sample.canonicalPath
            }
            current = current.parentFile
        }
        error("Cannot find sample-app.moqproj from ${System.getProperty("user.dir")}")
    }

    @BeforeTest
    fun setUp() {
        val binaryPath = try {
            FormatBinaryLocator.locate()
        } catch (e: FormatBinaryLocator.NotFoundException) {
            println("Skipping: ${e.message}")
            return
        }
        val proc = FormatProcess(locateBinary = { binaryPath }).apply { start() }
        process = proc
        repo = ProjectRepository(FormatClient(proc))
    }

    @AfterTest
    fun tearDown() {
        process?.stop()
    }

    private fun test(block: suspend (ProjectRepository) -> Unit) {
        val repo = repo ?: return
        runBlocking { withTimeout(15_000) { block(repo) } }
    }

    private fun tempProjectPath(label: String) =
        (System.getProperty("java.io.tmpdir") as String) + "/moqproj-$label-${UUID.randomUUID()}"

    // MARK: - Load

    @Test
    fun `load returns the sample project's full document`() = test { repo ->
        val project = repo.load(sampleProjectPath)

        assertEquals("1", project.manifest.version)
        assertEquals("Sample App API Mock", project.manifest.name)
        assertEquals(3, project.endpoints.size)

        val listUsers = project.endpoints.single { it.id == "list-users" }
        assertEquals("List Users", listUsers.alias)
        assertEquals("GET", listUsers.method)
        assertEquals(AuthType.BEARER, listUsers.auth?.type)
        assertEquals(4, listUsers.variants.size)

        val graphqlDocument = project.endpoints.single { it.id == "current-user" }
        assertEquals(OperationType.QUERY, graphqlDocument.operation?.type)
        assertNotNull(graphqlDocument.operation?.document)

        val graphqlNamed = project.endpoints.single { it.id == "get-user-profile" }
        assertEquals("GetUserProfile", graphqlNamed.operation?.name)
    }

    @Test
    fun `load surfaces a missing project as a catchable exception`() = test { repo ->
        assertFailsWith<FormatServiceException> {
            repo.load(tempProjectPath("does-not-exist"))
        }
    }

    // MARK: - Save / round-trip

    @Test
    fun `round trip preserves manifest, endpoint metadata, and variant fields`() = test { repo ->
        val loaded = repo.load(sampleProjectPath)
        val tempDir = tempProjectPath("roundtrip")
        try {
            repo.save(loaded, tempDir)
            val reloaded = repo.load(tempDir)

            assertEquals(loaded.manifest, reloaded.manifest)
            assertEquals(loaded.endpoints.size, reloaded.endpoints.size)
            for (endpoint in loaded.endpoints) {
                val reloadedEndpoint = reloaded.endpoints.single { it.id == endpoint.id }
                assertEquals(endpoint.method, reloadedEndpoint.method)
                assertEquals(endpoint.path, reloadedEndpoint.path)
                assertEquals(endpoint.alias, reloadedEndpoint.alias)
                assertEquals(endpoint.auth, reloadedEndpoint.auth)
                assertEquals(endpoint.variants.map { it.name }, reloadedEndpoint.variants.map { it.name })
                assertEquals(
                    endpoint.variants.map { it.description },
                    reloadedEndpoint.variants.map { it.description },
                )
            }
        } finally {
            File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `save replaces the endpoint set rather than merging it`() = test { repo ->
        val tempDir = tempProjectPath("replace")
        try {
            val first = MoqProject(
                manifest = manifest("Replace"),
                endpoints = listOf(endpoint("get-a", "/a")),
                projectPath = tempDir,
            )
            repo.save(first, tempDir)

            val second = MoqProject(
                manifest = manifest("Replace"),
                endpoints = listOf(endpoint("get-b", "/b")),
                projectPath = tempDir,
            )
            repo.save(second, tempDir)

            val reloaded = repo.load(tempDir)
            assertEquals(listOf("get-b"), reloaded.endpoints.map { it.id })
        } finally {
            File(tempDir).deleteRecursively()
        }
    }

    @Test
    fun `save as copies referenced fixtures to the new project directory`() = test { repo ->
        val originalDir = tempProjectPath("save-as-original")
        val newDir = tempProjectPath("save-as-new")
        try {
            val withInlineBody = MoqProject(
                manifest = manifest("SaveAs"),
                endpoints = listOf(
                    EndpointDocument(
                        id = "get-a",
                        method = "GET",
                        path = "/a",
                        variants = listOf(
                            ProjectVariant(name = "default", status = 200, body = YamlValue.Str("hello")),
                        ),
                    ),
                ),
                projectPath = originalDir,
            )
            repo.save(withInlineBody, originalDir)
            val afterFirstSave = repo.load(originalDir)
            val bodyFile = requireNotNull(afterFirstSave.endpoints.single().variants.single().bodyFile)

            // Save As: same bodyFile reference, new destination.
            repo.save(afterFirstSave.copy(projectPath = newDir), newDir)

            assertTrue(File(newDir, bodyFile).isFile, "fixture must have been copied to the new location")
        } finally {
            File(originalDir).deleteRecursively()
            File(newDir).deleteRecursively()
        }
    }

    @Test
    fun `inline text and base64 bodies round trip to the fixture bytes they represent`() = test { repo ->
        val tempDir = tempProjectPath("body-encoding")
        try {
            val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val project = MoqProject(
                manifest = manifest("BodyEncoding"),
                endpoints = listOf(
                    EndpointDocument(
                        id = "get-text",
                        method = "GET",
                        path = "/text",
                        variants = listOf(
                            ProjectVariant(name = "default", status = 200, body = YamlValue.Str("plain text")),
                        ),
                    ),
                    EndpointDocument(
                        id = "get-binary",
                        method = "GET",
                        path = "/binary",
                        variants = listOf(
                            ProjectVariant(
                                name = "default",
                                status = 200,
                                headers = mapOf("Content-Type" to "image/png"),
                                body = YamlValue.Str(Base64.getEncoder().encodeToString(raw)),
                                bodyEncoding = BodyEncoding.BASE64,
                            ),
                        ),
                    ),
                ),
                projectPath = tempDir,
            )
            repo.save(project, tempDir)
            val reloaded = repo.load(tempDir)

            val textFile = requireNotNull(reloaded.endpoints.single { it.id == "get-text" }.variants.single().bodyFile)
            assertEquals("plain text", File(tempDir, textFile).readText())

            val binaryFile =
                requireNotNull(reloaded.endpoints.single { it.id == "get-binary" }.variants.single().bodyFile)
            assertTrue(binaryFile.endsWith(".png"), "Content-Type should pick the fixture extension: $binaryFile")
            assertTrue(File(tempDir, binaryFile).readBytes().contentEquals(raw))
        } finally {
            File(tempDir).deleteRecursively()
        }
    }

    // MARK: - readFixture (pure local logic — no live process needed)

    @Test
    fun `readFixture returns contents for existing fixtures and null for missing ones`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-readfixture").toFile()
        try {
            File(tempDir, "fixtures").mkdirs()
            File(tempDir, "fixtures/body.json").writeText("{\"ok\":true}")
            val repo = localRepo()

            assertEquals("{\"ok\":true}", repo.readFixture(tempDir.absolutePath, "fixtures/body.json"))
            assertNull(repo.readFixture(tempDir.absolutePath, "fixtures/missing.json"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `readFixture refuses paths outside the fixtures directory`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-readfixture-traversal").toFile()
        val secret = File(tempDir.parentFile, "moqproj-secret-${tempDir.name}.txt").apply { writeText("secret") }
        try {
            val repo = localRepo()
            assertNull(repo.readFixture(tempDir.absolutePath, "../${secret.name}"))
            assertNull(repo.readFixture(tempDir.absolutePath, "fixtures/../../${secret.name}"))
        } finally {
            tempDir.deleteRecursively()
            secret.delete()
        }
    }

    /** A repository whose client is never invoked — valid only for the readFixture tests above. */
    private fun localRepo(): ProjectRepository =
        ProjectRepository(FormatClient(FormatProcess(locateBinary = { error("not used by readFixture") })))

    private fun manifest(name: String) = ProjectManifest(
        name = name,
        defaults = ProjectDefaults(
            auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
            network = NetworkBehavior(),
        ),
    )

    private fun endpoint(id: String, path: String) = EndpointDocument(
        id = id,
        method = "GET",
        path = path,
        variants = listOf(ProjectVariant(name = "default", status = 200)),
    )
}
