package com.moqserver.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import io.github.maniramezan.kmpcomponents.CheckStatus
import io.github.maniramezan.testcommons.CallRecorder
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.assertMinimumTouchTarget
import io.github.maniramezan.testcommons.compose.captureToPng
import io.github.maniramezan.testcommons.compose.replaceTextVerified
import io.github.maniramezan.testcommons.compose.waitForContentDescription
import io.github.maniramezan.testcommons.compose.waitForText
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioComponentsIntegrationTest {
	@get:Rule
	val compose = ThemedComposeRule(theme = { content ->
		StudioTheme(themeMode = StudioThemeMode.DARK) { Surface(Modifier.fillMaxSize(), content = content) }
	})

	@Test
	fun `provider edits stay unsaved and stale connection results cannot replace the edited status`() {
		val saved = CallRecorder<AISettings>()
		val checked = CallRecorder<Pair<String, AISettings>>()
		var reportStatus: (CheckStatus) -> Unit = {}
		compose.setContent {
			PreferencesScreen(
				state = PreferencesState(StudioThemeMode.DARK, AISettings()),
				onThemeModeChange = {},
				onSaveAISettings = saved::record,
				onTestAIProvider = { id, settings, onStatus ->
					checked.record(id to settings)
					reportStatus = onStatus
					onStatus(CheckStatus.Checking)
				},
			)
		}
		compose.waitForText("AI").performClick()
		compose.onNode(hasText("OpenAI") and hasClickAction()).performClick()
		compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("draft-secret")
		compose.onNodeWithText("Default Model").replaceTextVerified("draft-model")
		compose.onNodeWithText("Test Connection").performScrollTo().performClick()
		compose.runOnIdle {
			assertEquals(0, saved.count)
			assertEquals("openai", checked.lastValue()?.first)
			assertEquals("draft-secret", checked.lastValue()?.second?.openai?.apiKey)
			assertEquals("draft-model", checked.lastValue()?.second?.openai?.defaultModel)
		}
		compose.onNodeWithText("Default Model").performScrollTo().replaceTextVerified("edited-model")
		compose.runOnIdle { reportStatus(CheckStatus.Success("Stale connection result")) }
		compose.onNodeWithText("Stale connection result").assertDoesNotExist()
		compose.onNodeWithText("Save AI Settings").performScrollTo().performClick()
		compose.runOnIdle { assertEquals("edited-model", saved.lastValue()?.openai?.defaultModel) }
		captureEvidence("preferences-dark")
	}

	@Test
	fun `shared secret fields hide themselves again when the API key is cleared`() {
		compose.setContent {
			PreferencesScreen(PreferencesState(StudioThemeMode.DARK, AISettings()), {}, {}, { _, _, _ -> })
		}
		compose.waitForText("AI").performClick()
		compose.onNode(hasText("OpenAI") and hasClickAction()).performClick()
		compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("temporary-secret")
		compose.onNodeWithText("Show").performClick()
		compose.waitForText("Hide")
		compose.onNodeWithText("API Key").performTextClearance()
		compose.waitForText("Show")
		compose.onNodeWithText("Hide").assertDoesNotExist()
	}

	@Test
	fun `endpoint search can be cleared and shared rows select the endpoint`() {
		val project = browserProject()
		var state by mutableStateOf(StudioState(project = project))
		compose.setContent {
			Box(Modifier.fillMaxSize()) {
				EndpointBrowser(state, { state = state.copy(selectedEndpointId = it) }) { Text("Selected detail") }
			}
		}
		compose.waitForText("Search endpoints...").performTextReplacement("widgets")
		compose.onNodeWithText("/users").assertDoesNotExist()
		compose.waitForContentDescription("Clear endpoint search").assertMinimumTouchTarget().performClick()
		compose.onNode(hasText("/users") and hasClickAction()).performClick()
		compose.waitForText("Selected detail")
		compose.onNode(hasText("/users") and hasClickAction()).assertIsSelected()
		captureEvidence("endpoint-browser-dark")
	}

	@Test
	fun `endpoint navigation remains usable at double text size in RTL`() {
		compose.setContent {
			CompositionLocalProvider(
				LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
				LocalLayoutDirection provides LayoutDirection.Rtl,
			) {
				EndpointBrowser(StudioState(project = browserProject()), {}) { Text("Detail") }
			}
		}
		compose.waitForContentDescription("Resize endpoint browser").assertMinimumTouchTarget()
		compose.waitForText("Search endpoints...").performTextReplacement("no match")
		compose.waitForText("No matching endpoints")
		compose.waitForContentDescription("Clear endpoint search").performClick()
		compose.waitForText("/users")
		captureEvidence("endpoint-browser-rtl-large")
	}

	@Test
	fun `shared variant tabs preserve editing and require confirmation before deleting the default`() {
		val original = browserProject().endpoints.first().copy(
			variants = listOf(
				ProjectVariant(name = "Success", status = 200, isDefault = true),
				ProjectVariant(name = "Failure", status = 500),
			),
		)
		var endpoint by mutableStateOf(original)
		compose.setContent {
			StudioTheme(themeMode = StudioThemeMode.LIGHT) {
				Surface(Modifier.fillMaxSize()) {
					EndpointDetailPane(
						endpoint = endpoint,
						originalEndpoint = original,
						variantReferenceSyncPreference = VariantReferenceSyncPreference.NEVER_UPDATE,
						allEndpoints = listOf(endpoint),
						onUpdateEndpoint = { endpoint = it },
					)
				}
			}
		}
		compose.onNodeWithText("Body").performScrollTo().performClick()
		compose.waitForText("This variant has no response body yet.")
		compose.onNodeWithText("Criteria").performScrollTo().performClick()
		compose.onNodeWithText("Strict Call Count").performScrollTo().performClick()
		compose.runOnIdle { assertEquals(true, endpoint.strictCallCount) }
		compose.onNodeWithText("Summary").performScrollTo().performClick()
		compose.onNodeWithText("Variant Name").performScrollTo().replaceTextVerified("Edited Success")
		compose.runOnIdle { assertEquals("Edited Success", endpoint.variants.first().name) }
		compose.waitForContentDescription("Delete Edited Success").performScrollTo().performClick()
		compose.waitForText("Delete variant?")
		compose.runOnIdle { assertEquals(2, endpoint.variants.size) }
		compose.onNodeWithText("Cancel").performClick()
		compose.runOnIdle { assertEquals(2, endpoint.variants.size) }
		compose.waitForContentDescription("Delete Edited Success").performScrollTo().performClick()
		compose.waitForText("Delete variant?")
		compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
		compose.runOnIdle { assertEquals(listOf("Failure"), endpoint.variants.map { it.name }) }
		captureEvidence("endpoint-detail-light")
	}

	private fun captureEvidence(name: String) {
		val destination = File("build/reports/ui-integration/$name.png")
		destination.parentFile.mkdirs()
		destination.writeBytes(compose.onRoot().captureToPng())
	}

	private fun browserProject(): MoqProject = MoqProject(
		manifest = ProjectManifest(
            name = "Component integration",
            defaults = ProjectDefaults(
                auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                network = NetworkBehavior(),
            ),
        ),
		endpoints = listOf(
			EndpointDocument(
                id = "users",
                method = "GET",
                path = "/users",
                variants = listOf(ProjectVariant(name = "Success", status = 200)),
            ),
			EndpointDocument(
                id = "widgets",
                method = "GET",
                path = "/widgets",
                variants = listOf(ProjectVariant(name = "Success", status = 200)),
            ),
		),
		projectPath = "/tmp/component-integration.moqproj",
	)
}
