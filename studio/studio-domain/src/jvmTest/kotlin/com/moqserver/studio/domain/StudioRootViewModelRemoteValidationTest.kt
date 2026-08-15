package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import com.moqserver.studio.projectformat.format.RemoteProjectValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the exact composition Main.kt wires: FormatProcess -> FormatClient ->
 * RemoteProjectValidator -> `StudioRootViewModel(validate = ...)`, end to end against the real
 * moq-format binary. Compose's own `LaunchedEffect`-driven open path isn't reachable headlessly
 * here, so this drives `projectLoaded` directly - the same call `openProject` in
 * ProjectOperations.kt makes - and polls `state.diagnostics` the way `ValidationPanel` would.
 *
 * Skips (doesn't fail) when no binary is configured; see FormatClientIntegrationTest.
 */
class StudioRootViewModelRemoteValidationTest {
    private var process: FormatProcess? = null

    @BeforeTest
    fun setUp() {
        val binaryPath = try {
            FormatBinaryLocator.locate()
        } catch (e: FormatBinaryLocator.NotFoundException) {
            println("Skipping StudioRootViewModelRemoteValidationTest: ${e.message}")
            return
        }
        process = FormatProcess(locateBinary = { binaryPath }).apply { start() }
    }

    @AfterTest
    fun tearDown() {
        process?.stop()
    }

    @Test
    fun `projectLoaded schedules validation that arrives in state through the real binary`() {
        val process = process ?: return
        val validator = RemoteProjectValidator(FormatClient(process))
        val viewModel = StudioRootViewModel(validate = validator::validate)

        // Missing variants on the endpoint -> a real, server-produced diagnostic.
        val project = MoqProject(
            manifest = ProjectManifest(
                name = "Remote Validation Smoke",
                defaults = ProjectDefaults(
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = listOf(
                EndpointDocument(id = "get-a", method = "GET", path = "/a", variants = emptyList()),
            ),
            projectPath = "/tmp/remote-validation-smoke",
        )

        runBlocking {
            withTimeout(15_000) {
                viewModel.projectLoaded(project)
                val diagnostics = viewModel.state
                    .first { it.diagnostics.isNotEmpty() }
                    .diagnostics
                assertTrue(diagnostics.any { it.code == "E_NO_VARIANTS" }, "diagnostics: $diagnostics")
            }
        }
    }

    @Test
    fun `a clean project settles to zero errors`() {
        val process = process ?: return
        val validator = RemoteProjectValidator(FormatClient(process))
        val viewModel = StudioRootViewModel(validate = validator::validate)

        val project = MoqProject(
            manifest = ProjectManifest(
                name = "Clean",
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
                    variants = listOf(ProjectVariant(name = "default", status = 200)),
                ),
            ),
            projectPath = "/tmp/remote-validation-clean",
        )

        runBlocking {
            withTimeout(15_000) {
                // A direct call proves the service round-trips cleanly...
                val direct = validator.validate(project)
                assertTrue(
                    direct.none { it.severity == com.moqserver.studio.projectformat.ValidationDiagnostic.Severity.ERROR },
                )

                // ...and this proves the ViewModel's async scheduling actually delivers that
                // result into state, not just that the underlying call works. The initial state
                // right after projectLoaded() already has empty diagnostics (nothing has run
                // yet), so a bounded wait rather than a first{} predicate is needed here -
                // otherwise the assertion would trivially pass before validation ever executes.
                delay(2_000)
                assertTrue(
                    viewModel.state.value.diagnostics.none {
                    it.severity == com.moqserver.studio.projectformat.ValidationDiagnostic.Severity.ERROR
                },
                )
            }
        }
    }
}
