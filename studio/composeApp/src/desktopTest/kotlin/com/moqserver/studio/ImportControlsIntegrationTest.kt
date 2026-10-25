package com.moqserver.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.moqserver.studio.domain.ImportAIBulkState
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.captureToPng
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportControlsIntegrationTest {
	@get:Rule
	val compose = ThemedComposeRule(theme = { content ->
		StudioTheme(themeMode = StudioThemeMode.LIGHT) { Surface(Modifier.fillMaxSize(), content = content) }
	})

	@Test
	fun `shared endpoint and generated-response checkboxes update the import selections`() {
		var state by mutableStateOf(importState())
		compose.setContent {
			Review(
				state = state,
				onToggleEndpoint = { state = state.toggleEndpoint(it) },
				onToggleGenerated = { index, response -> state = state.toggleGeneratedResponse(index, response) },
			)
		}
		compose.onNode(hasText("/items") and isToggleable()).performScrollTo().performClick()
		compose.runOnIdle { assertEquals(false, state.entries.single().accepted) }
		compose.onNode(hasText("/items") and isToggleable()).performClick()
		compose.onNodeWithText("Details").performClick()
		compose.onNode(hasText("Generated failure") and isToggleable()).performScrollTo().performClick()
		compose.runOnIdle { assertEquals(emptySet(), state.entries.single().selectedGeneratedResponseIndices) }
		val destination = File("build/reports/ui-integration/import-shared-controls.png")
		destination.parentFile.mkdirs()
		destination.writeBytes(compose.onRoot().captureToPng())
	}

	@Test
	fun `shared bulk progress reflects the completed endpoint count`() {
		val state = importState(warnings = listOf("Missing response schema")).copy(
			aiBulkState = ImportAIBulkState(running = true, completedCount = 1, totalCount = 2),
		)
		compose.setContent { Review(state) }
		compose.onNodeWithText("Missing response schema").assertExists()
		compose.onNodeWithText("Generating AI mocks 1/2").assertExists()
		compose.onNode(
			SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)),
			useUnmergedTree = true,
		).assertExists()
	}

	@Composable
	private fun Review(
		state: ImportState,
		onToggleEndpoint: (Int) -> Unit = {},
		onToggleGenerated: (Int, Int) -> Unit = { _, _ -> },
	) {
		ImportReviewScreen(
			state = state,
			aiProviders = emptyList(),
			aiProvidersLoading = false,
			canGenerateWithAi = false,
			selectedAIProviderId = null,
			aiProviderLabel = null,
			onRefreshAIProviders = {},
			onSelectAIProvider = {},
			onToggleEndpoint = onToggleEndpoint,
			onSetGroupAccepted = { _, _ -> },
			onSelectAll = {},
			onDeselectAll = {},
			onUpdateProjectName = {},
			onUpdateSelection = {},
			onUpdateResponseName = { _, _, _ -> },
			onUpdateGeneratedResponseName = { _, _, _ -> },
			onToggleGeneratedResponse = onToggleGenerated,
			onUpdateEndpointAIContextHint = { _, _ -> },
			onGenerateEndpointMocks = {},
			onGenerateAllMocks = {},
			onConfirm = {},
			onCancel = {},
		)
	}

	private fun importState(warnings: List<String> = emptyList()): ImportState {
		val endpoint = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "Success", statusCode = 200)),
		)
		return ImportState(
			source = ImportSourceType.OPENAPI,
			sourceFileName = "items.yaml",
			parsedSpec = ParsedSpec(title = "Items", version = "1", endpoints = listOf(endpoint), warnings = warnings),
			entries = listOf(
				ImportEndpointEntry(
					endpoint = endpoint,
					generatedResponses = listOf(ParsedResponse(name = "Generated failure", statusCode = 500)),
					selectedGeneratedResponseIndices = setOf(0),
				),
			),
			projectName = "Items",
		)
	}

	private fun ImportState.toggleEndpoint(index: Int): ImportState = copy(
		entries = entries.mapIndexed { current, entry ->
			if (current == index) entry.copy(accepted = !entry.accepted) else entry
		},
	)

	private fun ImportState.toggleGeneratedResponse(index: Int, response: Int): ImportState = copy(
		entries = entries.mapIndexed { current, entry ->
			if (current == index) {
				val selected = entry.selectedGeneratedResponseIndices
				entry.copy(
					selectedGeneratedResponseIndices = if (response in selected) selected - response else selected + response,
				)
			} else {
				entry
			}
		},
	)
}
