package com.moqserver.studio.projectformat.format

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end against the real `moq-format` binary — not a mock of the wire protocol. Requires a
 * built binary; skips (does not fail) when one can't be located, since this repo's Swift and
 * Kotlin toolchains aren't both guaranteed present in every environment that runs `jvmTest`.
 *
 * Locate it with `MOQSERVER_FORMAT_BINARY`, or build it first:
 *   cd server && swift build --product moq-format
 *   export MOQSERVER_FORMAT_BINARY="$(cd server && swift build --show-bin-path)/moq-format"
 */
class FormatClientIntegrationTest {
    private var process: FormatProcess? = null
    private var client: FormatClient? = null

    @BeforeTest
    fun setUp() {
        val binaryPath = try {
            FormatBinaryLocator.locate()
        } catch (e: FormatBinaryLocator.NotFoundException) {
            println("Skipping FormatClientIntegrationTest: ${e.message}")
            return
        }
        val proc = FormatProcess(locateBinary = { binaryPath })
        proc.start()
        process = proc
        client = FormatClient(proc)
    }

    @AfterTest
    fun tearDown() {
        process?.stop()
    }

    private fun withClient(block: suspend (FormatClient) -> Unit) {
        val client = client ?: return
        runBlocking {
            withTimeout(15_000) { block(client) }
        }
    }

    private fun manifest(name: String = "Integration") = ProjectManifest(
        name = name,
        defaults = ProjectDefaults(
            auth = ProjectAuthConfig(type = com.moqserver.studio.projectformat.AuthType.NONE, verify = false),
            network = NetworkBehavior(),
        ),
    )

    private fun tempPath(label: String) =
        File(System.getProperty("java.io.tmpdir"), "format-client-$label-${UUID.randomUUID()}.moqproj").absolutePath

    @Test
    fun `becomes ready and reports state`() = withClient { _ ->
        val state = process!!.state.first { it !is FormatServiceState.Starting }
        assertTrue(state is FormatServiceState.Ready, "expected Ready, got $state")
    }

    @Test
    fun `stateless validate flags an invalid in-memory project`() = withClient { client ->
        val project = MoqProject(manifest = manifest(), endpoints = emptyList(), projectPath = "/tmp/nope")
        val result = client.validateProject(project)
        assertTrue(result.errorCount > 0)
        assertTrue(result.diagnostics.any { it.code == "E_NO_ENDPOINTS" })
    }

    @Test
    fun `full session lifecycle round trips through the real process`() = withClient { client ->
        val path = tempPath("lifecycle")
        try {
            val handle = client.openSession()
            val description = client.createProject(handle, name = "Lifecycle", description = null, path = path)
            assertEquals("Lifecycle", description.name)

            val endpoint = client.upsertEndpoint(
                handle,
                EndpointUpsertInput(id = "get-a", method = "GET", path = "/a"),
                autosave = false,
            )
            assertEquals("get-a", endpoint.id)

            val beforeVariant = client.validateProject(handle)
            assertTrue(beforeVariant.diagnostics.any { it.code == "E_NO_VARIANTS" })

            client.upsertVariant(
                handle,
                "get-a",
                com.moqserver.studio.projectformat.ProjectVariant(name = "default", status = 200),
                autosave = false,
            )
            val afterVariant = client.validateProject(handle)
            assertEquals(0, afterVariant.errorCount)

            client.saveProject(handle)
            val endpoints = client.listEndpoints(handle)
            assertEquals(listOf("get-a"), endpoints.map { it.id })

            client.closeSession(handle)
        } finally {
            File(path).deleteRecursively()
        }
    }

    @Test
    fun `call_count and strict_call_count round trip through the real process`() = withClient { client ->
        val path = tempPath("call-count")
        try {
            val handle = client.openSession()
            client.createProject(handle, name = "Call Count", description = null, path = path)

            val endpoint = client.upsertEndpoint(
                handle,
                EndpointUpsertInput(id = "get-job", method = "GET", path = "/jobs/1", strictCallCount = true),
                autosave = false,
            )
            assertEquals(true, endpoint.strictCallCount)

            client.upsertVariant(
                handle,
                "get-job",
                com.moqserver.studio.projectformat.ProjectVariant(name = "pending", status = 200, callCount = 1),
                autosave = false,
            )

            client.saveProject(handle)
            val endpoints = client.listEndpoints(handle)
            val reloaded = client.getEndpoint(handle, endpoints.single().id)
            assertEquals(true, reloaded.strictCallCount)
            assertEquals(1, reloaded.variants.single().callCount)

            client.closeSession(handle)
        } finally {
            File(path).deleteRecursively()
        }
    }

    @Test
    fun `unsaved changes are reported through error data code`() = withClient { client ->
        val originalPath = tempPath("guard-original")
        val otherPath = tempPath("guard-other")
        try {
            val handle = client.openSession()
            client.createProject(handle, name = "Guard", description = null, path = originalPath)
            client.upsertEndpoint(
                handle,
                EndpointUpsertInput(id = "get-a", method = "GET", path = "/a"),
                autosave = false,
            )

            val otherHandle = client.openSession()
            client.createProject(otherHandle, name = "Other", description = null, path = otherPath)
            client.closeSession(otherHandle)

            val exception = assertFailsWith<FormatServiceException> {
                client.openProject(handle, otherPath, force = false)
            }
            assertEquals("E_UNSAVED_CHANGES", exception.code)
        } finally {
            File(originalPath).deleteRecursively()
            File(otherPath).deleteRecursively()
        }
    }

    @Test
    fun `suggestEndpointId matches the id pattern moqserver would assign`() = withClient { client ->
        val identity = client.suggestEndpointId("POST", "/users/{id}")
        assertEquals("post-users-param", identity.id)
    }

    /**
     * Regression test for a real bug found while manually verifying the app: a variant with only
     * `bodyFile` set (no inline `body`) falsely failed validation with E_BODY_AND_BODY_FILE when
     * the whole project was hand-constructed with `body = null` and sent through the stateless
     * `validate` call. The cause was `Json { encodeDefaults = true }` forcing `body: null` onto
     * the wire explicitly, and Swift's `ProjectVariant` decoder treating a present-but-null
     * `body` as `AnyCodableValue.null` (a real, distinct case for reading `body: null` from an
     * actual .moqproj file) rather than as absent — manufacturing a body where the client had
     * none. Fixed by encoding only what's actually present rather than blanket-encoding
     * defaults; this test constructs the project fresh (not round-tripped from a prior load, so
     * it can't accidentally pass only because the server-side value already matches) to catch
     * a regression on the encode side specifically.
     */
    @Test
    fun `a fresh project with a body_file-only variant validates clean, not E_BODY_AND_BODY_FILE`() =
        withClient { client ->
            val project = MoqProject(
                manifest = ProjectManifest(
                    name = "Regression",
                    defaults = ProjectDefaults(
                        auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                        network = NetworkBehavior(),
                    ),
                ),
                endpoints = listOf(
                    EndpointDocument(
                        id = "get-a",
                        method = "GET",
                        path = "/a",
                        variants = listOf(
                            ProjectVariant(
                                name = "default",
                                status = 200,
                                body = null,
                                bodyFile = "fixtures/responses/a.json",
                            ),
                        ),
                    ),
                ),
                projectPath = "/tmp/regression-body-and-body-file",
            )

            val result = client.validateProject(project)
            assertTrue(
                result.diagnostics.none { it.code == "E_BODY_AND_BODY_FILE" },
                "diagnostics: ${result.diagnostics}",
            )
        }
}
