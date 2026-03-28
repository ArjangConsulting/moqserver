package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioRootViewModelTest {

    @Test
    fun `ai state starts empty and not loading`() {
        val state = StudioRootViewModel().state.value.ai

        assertFalse(state.loading)
        assertTrue(state.providers.isEmpty())
        assertNull(state.error)
        assertFalse(state.isReady)
    }

    @Test
    fun `aiProvidersLoaded selects first available provider`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            AIProviderInfo(
                id = "offline",
                displayName = "Offline",
                kind = ProviderKind.LOCAL,
                available = false,
                capabilities = emptySet(),
            ),
            AIProviderInfo(
                id = "ollama",
                displayName = "Ollama",
                kind = ProviderKind.LOCAL,
                available = true,
                capabilities = setOf("GENERATE_VARIANTS"),
            ),
        )

        viewModel.aiProvidersLoaded(providers)

        val state = viewModel.state.value.ai
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("ollama", state.selectedProviderId)
        assertTrue(state.isReady)
    }

    @Test
    fun `aiProvidersLoadFailed records error`() {
        val viewModel = StudioRootViewModel()

        viewModel.aiProvidersLoadFailed("Connection refused")

        val state = viewModel.state.value.ai
        assertFalse(state.loading)
        assertEquals("Connection refused", state.error)
        assertNull(state.selectedProviderId)
        assertFalse(state.isReady)
    }

    @Test
    fun `aiProvidersLoaded preserves an existing available selection`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            AIProviderInfo(
                id = "openai",
                displayName = "OpenAI",
                kind = ProviderKind.HOSTED,
                available = true,
                capabilities = emptySet(),
            ),
            AIProviderInfo(
                id = "ollama",
                displayName = "Ollama",
                kind = ProviderKind.LOCAL,
                available = true,
                capabilities = emptySet(),
            ),
        )

        viewModel.selectProvider("openai")
        viewModel.aiProvidersLoaded(providers)

        assertEquals("openai", viewModel.state.value.ai.selectedProviderId)
        assertTrue(viewModel.state.value.ai.isReady)
    }

    @Test
    fun `aiProvidersLoaded falls back when existing selection disappears`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            AIProviderInfo(
                id = "ollama",
                displayName = "Ollama",
                kind = ProviderKind.LOCAL,
                available = true,
                capabilities = emptySet(),
            ),
        )

        viewModel.selectProvider("openai")
        viewModel.aiProvidersLoaded(providers)

        assertEquals("ollama", viewModel.state.value.ai.selectedProviderId)
        assertTrue(viewModel.state.value.ai.isReady)
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
