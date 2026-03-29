package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals

import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest

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
}
