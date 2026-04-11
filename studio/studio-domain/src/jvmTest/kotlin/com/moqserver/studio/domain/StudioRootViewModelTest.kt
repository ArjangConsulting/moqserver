package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioRootViewModelTest {

    private fun sampleImportState(): ImportState {
        val endpoint = ParsedEndpoint(
            method = "GET",
            path = "/users",
            responses = listOf(
                ParsedResponse(name = "default", statusCode = 200, body = "[]"),
            ),
        )
        return ImportState(
            source = ImportSourceType.OPENAPI,
            sourceFileName = "users.yaml",
            parsedSpec = ParsedSpec(
                title = "Users API",
                version = "1.0.0",
                endpoints = listOf(endpoint),
            ),
            entries = listOf(ImportEndpointEntry(endpoint = endpoint)),
            projectName = "Users API",
        )
    }

    private fun sampleProject() = MoqProject(
        manifest = ProjectManifest(
            name = "Test Project",
            defaults = ProjectDefaults(
                delayMs = 0,
                auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                network = NetworkBehavior(latencyMs = 0, jitterMs = 0, packetLossPercent = 0.0),
            ),
        ),
        endpoints = listOf(
            EndpointDocument(
                id = "get-users",
                method = "GET",
                path = "/users",
                variants = listOf(
                    ProjectVariant(name = "Success", status = 200, isDefault = true),
                ),
            ),
        ),
        projectPath = "/tmp/test.moqproj",
    )

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
                defaultModel = "llama3.2:latest",
                baseUrl = "http://localhost:11434",
            ),
        )

        viewModel.aiProvidersLoaded(providers)

        val state = viewModel.state.value.ai
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("ollama", state.selectedProviderId)
        assertTrue(state.isReady)
        assertEquals("llama3.2:latest", state.selectedProvider?.defaultModel)
        assertEquals("http://localhost:11434", state.selectedProvider?.baseUrl)
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
    fun `aiProvidersLoaded preserves existing selection when provider is temporarily unavailable`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            AIProviderInfo(
                id = "openai",
                displayName = "OpenAI",
                kind = ProviderKind.HOSTED,
                available = false,
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
        assertFalse(viewModel.state.value.ai.isReady)
    }

    @Test
    fun `selected provider exposes connection details`() {
        val viewModel = StudioRootViewModel()
        val providers = listOf(
            AIProviderInfo(
                id = "openai",
                displayName = "OpenAI",
                kind = ProviderKind.HOSTED,
                available = true,
                capabilities = setOf("ANALYZE_SPEC", "GENERATE_VARIANTS"),
                defaultModel = "gpt-4o",
                baseUrl = "https://api.openai.com/v1",
            )
        )

        viewModel.aiProvidersLoaded(providers)

        val selectedProvider = viewModel.state.value.ai.selectedProvider
        assertEquals("OpenAI", selectedProvider?.displayName)
        assertEquals("gpt-4o", selectedProvider?.defaultModel)
        assertEquals("https://api.openai.com/v1", selectedProvider?.baseUrl)
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

    @Test
    fun `removeRecentProject removes only the selected path`() {
        val viewModel = StudioRootViewModel()

        viewModel.setRecentProjects(listOf("/tmp/first.moqproj", "/tmp/second.moqproj"))
        viewModel.removeRecentProject("/tmp/first.moqproj")

        assertEquals(listOf("/tmp/second.moqproj"), viewModel.state.value.recentProjects)
    }

    @Test
    fun `updateEndpoint does not mark project dirty when endpoint is unchanged`() {
        val viewModel = StudioRootViewModel()
        val project = sampleProject()

        viewModel.projectLoaded(project)
        viewModel.updateEndpoint(project.endpoints.single())

        val state = viewModel.state.value
        assertFalse(state.isDirty)
        assertEquals(project, state.project)
    }

    @Test
    fun `updateManifest does not mark project dirty when manifest is unchanged`() {
        val viewModel = StudioRootViewModel()
        val project = sampleProject()

        viewModel.projectLoaded(project)
        viewModel.updateManifest(project.manifest)

        val state = viewModel.state.value
        assertFalse(state.isDirty)
        assertEquals(project, state.project)
    }

    @Test
    fun `undo after rapid description edits restores original state in one step`() {
        val viewModel = StudioRootViewModel()
        val project = sampleProject().copy(
            endpoints = listOf(
                sampleProject().endpoints.single().copy(description = "alpha beta"),
            ),
        )

        viewModel.projectLoaded(project)
        val endpoint = project.endpoints.single()

        viewModel.updateEndpoint(endpoint.copy(description = "alpha "))
        viewModel.updateEndpoint(endpoint.copy(description = "alpha"))
        viewModel.undo()

        val restoredEndpoint = viewModel.state.value.project?.endpoints?.single()
        assertEquals("alpha beta", restoredEndpoint?.description)
        assertTrue(viewModel.state.value.canRedo)
    }

    @Test
    fun `edit after undo creates a fresh undo step`() {
        val viewModel = StudioRootViewModel()
        val project = sampleProject().copy(
            endpoints = listOf(
                sampleProject().endpoints.single().copy(description = "alpha beta"),
            ),
        )

        viewModel.projectLoaded(project)
        val endpoint = project.endpoints.single()

        viewModel.updateEndpoint(endpoint.copy(description = "alpha"))
        viewModel.undo()
        viewModel.updateEndpoint(endpoint.copy(description = "gamma"))
        viewModel.undo()

        val restoredEndpoint = viewModel.state.value.project?.endpoints?.single()
        assertEquals("alpha beta", restoredEndpoint?.description)
    }

    @Test
    fun `updateEndpoint rejects marking second default variant and preserves current state`() {
        val viewModel = StudioRootViewModel()
        val project = sampleProject().copy(
            endpoints = listOf(
                sampleProject().endpoints.single().copy(
                    variants = listOf(
                        ProjectVariant(name = "Success", status = 200, isDefault = true),
                        ProjectVariant(name = "Failure", status = 500),
                    ),
                ),
            ),
        )

        viewModel.projectLoaded(project)
        viewModel.updateEndpoint(
            project.endpoints.single().copy(
                variants = listOf(
                    ProjectVariant(name = "Success", status = 200, isDefault = true),
                    ProjectVariant(name = "Failure", status = 500, isDefault = true),
                ),
            ),
        )

        val state = viewModel.state.value
        assertEquals(project, state.project)
        assertFalse(state.isDirty)
        assertEquals(
            "\"Success\" is already the default variant. Clear it before marking \"Failure\" as default.",
            state.transientDiagnostic?.message,
        )
        assertEquals("get-users", state.transientDiagnostic?.endpointId)
        assertEquals("Failure", state.transientDiagnostic?.variantName)
    }

    @Test
    fun `dismissError clears transient workspace error`() {
        val viewModel = StudioRootViewModel()

        viewModel.setError("Something went wrong")
        viewModel.dismissError()

        assertNull(viewModel.state.value.transientDiagnostic)
        assertEquals("Error: Something went wrong", viewModel.state.value.statusLine)
    }

    @Test
    fun `importAIGenerationCompleted stores generated responses on entry`() {
        val viewModel = StudioRootViewModel()

        viewModel.startImport(sampleImportState().parsedSpec, ImportSourceType.OPENAPI, "users.yaml")
        viewModel.importAIGenerationStarted(0)
        viewModel.importAIGenerationCompleted(
            index = 0,
            generatedResponses = listOf(
                ParsedResponse(
                    name = "not-found",
                    statusCode = 404,
                    body = "{\"error\":\"missing\"}",
                ),
            ),
        )

        val entry = viewModel.state.value.importState!!.entries.single()
        assertFalse(entry.aiGenerationLoading)
        assertNull(entry.aiGenerationError)
        assertEquals(1, entry.generatedResponses.size)
        assertEquals("not-found", entry.generatedResponses.single().name)
    }

    @Test
    fun `importAIBulkStarted and finished track bulk progress`() {
        val viewModel = StudioRootViewModel()

        viewModel.startImport(sampleImportState().parsedSpec, ImportSourceType.OPENAPI, "users.yaml")
        viewModel.importAIBulkStarted(totalCount = 3)
        viewModel.importAIBulkProgress(completedCount = 2)
        viewModel.importAIBulkFinished()

        val bulkState = viewModel.state.value.importState!!.aiBulkState
        assertFalse(bulkState.running)
        assertEquals(3, bulkState.totalCount)
        assertEquals(3, bulkState.completedCount)
    }
}
