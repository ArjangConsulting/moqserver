package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioRootViewModelTest {

    @Test
    fun `companion starts idle until explicitly checked`() {
        val state = StudioRootViewModel().state.value.companion

        assertTrue(state.isIdle)
        assertFalse(state.hasChecked)
        assertFalse(state.connected)
    }

    @Test
    fun `companionConnected marks state checked and selects first available provider`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            CompanionProvider(
                id = "offline",
                displayName = "Offline",
                kind = ProviderKind.LOCAL,
                available = false,
                capabilities = emptyList(),
            ),
            CompanionProvider(
                id = "ollama",
                displayName = "Ollama",
                kind = ProviderKind.LOCAL,
                available = true,
                capabilities = listOf(AICapability.GENERATE_VARIANTS),
            ),
        )

        viewModel.companionConnected(providers)

        val state = viewModel.state.value.companion
        assertTrue(state.hasChecked)
        assertTrue(state.connected)
        assertEquals("ollama", state.selectedProviderId)
        assertFalse(state.isIdle)
    }

    @Test
    fun `companionDisconnected preserves non-fatal offline state`() {
        val viewModel = StudioRootViewModel()

        viewModel.companionDisconnected("Connection refused")

        val state = viewModel.state.value.companion
        assertTrue(state.hasChecked)
        assertFalse(state.connected)
        assertEquals("Connection refused", state.error)
        assertNull(state.selectedProviderId)
        assertFalse(state.isIdle)
    }

    @Test
    fun `projectClosed clears project but preserves recent projects`() {
        val viewModel = StudioRootViewModel()

        viewModel.addRecentProject("/tmp/first.moqproj")
        viewModel.projectClosed()

        val state = viewModel.state.value
        assertNull(state.project)
        assertEquals(listOf("/tmp/first.moqproj"), state.recentProjects)
        assertEquals("Project closed. Open a .moqproj directory to get started.", state.statusLine)
    }
}
