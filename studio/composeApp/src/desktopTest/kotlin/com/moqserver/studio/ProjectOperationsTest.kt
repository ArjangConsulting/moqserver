package com.moqserver.studio

import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectOperationsTest {

    @Test
    fun `resolveWindowCloseAction closes current project when one is open`() {
        val state = StudioState(project = sampleProject())

        assertEquals(WindowCloseAction.CLOSE_PROJECT, resolveWindowCloseAction(state))
    }

    @Test
    fun `resolveWindowCloseAction exits application from landing screen`() {
        assertEquals(WindowCloseAction.EXIT_APPLICATION, resolveWindowCloseAction(StudioState()))
    }

    @Test
    fun `recentProjectLabel uses last path segment`() {
        assertEquals("Sample.moqproj", recentProjectLabel("/tmp/path/Sample.moqproj"))
    }

    @Test
    fun `isMacOs returns true on mac`() {
        withOsName("Mac OS X") {
            assertTrue(isMacOs())
        }
    }

    @Test
    fun `isMacOs returns false on non-mac`() {
        withOsName("Linux") {
            assertFalse(isMacOs())
        }
    }

    private fun sampleProject(): MoqProject {
        return MoqProject(
            manifest = ProjectManifest(
                name = "Sample Project",
                defaults = ProjectDefaults(
                    delayMs = 0,
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = emptyList(),
            projectPath = "/tmp/sample.moqproj",
        )
    }

    private fun withOsName(value: String, block: () -> Unit) {
        val previous = System.getProperty("os.name")
        try {
            System.setProperty("os.name", value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("os.name")
            } else {
                System.setProperty("os.name", previous)
            }
        }
    }
}
