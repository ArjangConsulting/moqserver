package com.moqserver.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.moqserver.studio.endpointdetail.BodyTab
import com.moqserver.studio.endpointdetail.CookiesTab
import com.moqserver.studio.endpointdetail.HeadersTab
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.assertMinimumTouchTarget
import io.github.maniramezan.testcommons.compose.captureToPng
import io.github.maniramezan.testcommons.compose.replaceTextVerified
import io.github.maniramezan.testcommons.compose.waitForContentDescription
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorControlsIntegrationTest {
	@get:Rule
	val compose = ThemedComposeRule(theme = { content ->
		StudioTheme(themeMode = StudioThemeMode.LIGHT) { Surface(Modifier.fillMaxSize(), content = content) }
	})

	@Test
	fun `shared header controls preserve matching rules while editing and deleting a header`() {
		var headers by mutableStateOf(mapOf("X-Test" to "original"))
		var rules by mutableStateOf(RequestRules(cookies = listOf(RuleMatcher(name = "session"))))
		compose.setContent {
			Column {
				HeadersTab(headers, rules) { updatedHeaders, updatedRules ->
					headers = updatedHeaders
					rules = updatedRules
				}
			}
		}
		field("Header Value").performScrollTo().replaceTextVerified("updated")
		compose.onNode(hasText("Required") and isToggleable()).performScrollTo().assertMinimumTouchTarget().performClick()
		field("Header Name").performScrollTo().replaceTextVerified("X-Renamed")
		field("Match value").performScrollTo().replaceTextVerified("expected")
		compose.runOnIdle {
			assertEquals(mapOf("X-Renamed" to "updated"), headers)
			assertEquals(
				RuleMatcher(name = "X-Renamed", required = true, matchType = MatchType.EQUAL_TO, match = "expected"),
				rules.headers?.single(),
			)
		}
		compose.onNodeWithText("Delete header").performScrollTo().assertMinimumTouchTarget().performClick()
		compose.runOnIdle {
			assertTrue(headers.isEmpty())
			assertNull(rules.headers)
			assertEquals("session", rules.cookies?.single()?.name)
		}
	}

	@Test
	fun `cookie matching and deletion remain reachable with large RTL text`() {
		var rules by mutableStateOf(RequestRules(cookies = listOf(RuleMatcher(name = "session", match = "original"))))
		compose.setContent {
			CompositionLocalProvider(
				LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
				LocalLayoutDirection provides LayoutDirection.Rtl,
			) {
				Column { CookiesTab(rules) { rules = it } }
			}
		}
		field("Cookie Value").performScrollTo().replaceTextVerified("optional-value")
		compose.onNodeWithText("Match value").performScrollTo().assertIsNotEnabled()
		compose.onNode(hasText("Required") and isToggleable()).performScrollTo().assertMinimumTouchTarget().performClick()
		field("Match value").performScrollTo().replaceTextVerified("required-value")
		compose.runOnIdle {
			assertEquals("required-value", rules.cookies?.single()?.match)
			assertEquals(MatchType.EQUAL_TO, rules.cookies?.single()?.matchType)
		}
		compose.onNodeWithText("Delete cookie").performScrollTo().assertMinimumTouchTarget()
		captureEvidence("cookie-table-rtl-large")
		compose.onNodeWithText("Delete cookie").performClick()
		compose.runOnIdle { assertNull(rules.cookies) }
	}

	@Test
	fun `shared body actions keep edits local until save and discard a canceled draft`() {
		var variant by mutableStateOf(
			ProjectVariant(
				name = "Success",
				status = 200,
				headers = mapOf("Content-Type" to "application/json"),
				body = YamlValue.Obj(mapOf("count" to YamlValue.Int(1))),
			),
		)
		compose.setContent {
			Column {
				BodyTab(
					endpointMethod = "GET",
					endpointPath = "/items",
					variant = variant,
					aiProviderLabel = null,
					canGenerateWithAi = false,
					isGeneratingWithAi = false,
					generationError = null,
					onGenerateBody = {},
					onUpdate = { variant = it },
				)
			}
		}
		compose.waitForContentDescription("Edit body").performClick()
		compose.onNode(hasSetTextAction()).performTextReplacement("""{"count":2}""")
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(1))), variant.body) }
		compose.waitForContentDescription("Save body edit").performClick()
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(2))), variant.body) }
		compose.waitForContentDescription("Edit body").performClick()
		compose.onNode(hasSetTextAction()).performTextReplacement("""{"count":3}""")
		compose.waitForContentDescription("Cancel body edit").performClick()
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(2))), variant.body) }
		captureEvidence("body-shared-actions-light")
	}

	private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

	private fun captureEvidence(name: String) {
		val destination = File("build/reports/ui-integration/$name.png")
		destination.parentFile.mkdirs()
		destination.writeBytes(compose.onAllNodes(isRoot())[0].captureToPng())
	}
}
