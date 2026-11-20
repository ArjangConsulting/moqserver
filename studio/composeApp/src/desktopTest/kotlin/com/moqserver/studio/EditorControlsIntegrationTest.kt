package com.moqserver.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.moqserver.studio.endpointdetail.BodyTab
import com.moqserver.studio.endpointdetail.CookiesTab
import com.moqserver.studio.endpointdetail.CriteriaTab
import com.moqserver.studio.endpointdetail.HeadersTab
import com.moqserver.studio.endpointdetail.StreamSettingsSection
import com.moqserver.studio.endpointdetail.defaultResponseStream
import com.moqserver.studio.projectformat.BodyEncoding
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.ResponseStream
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
		compose.runOnIdle {
			assertEquals(RuleMatcher(name = "X-Test", required = true, matchType = MatchType.REQUIRE), rules.headers?.single())
		}
		chooseCondition(from = "exists", to = "equals")
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
	fun `cookie rules switch between required and optional-but-checked with large RTL text`() {
		var rules by mutableStateOf(RequestRules(cookies = listOf(RuleMatcher(name = "session", match = "original"))))
		compose.setContent {
			CompositionLocalProvider(
				LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
				LocalLayoutDirection provides LayoutDirection.Rtl,
			) {
				Column { CookiesTab(rules) { rules = it } }
			}
		}
		field("Match value").performScrollTo().replaceTextVerified("checked-value")
		val required = compose.onNode(hasText("Required") and isToggleable())
		required.performScrollTo().assertMinimumTouchTarget().performClick()
		compose.runOnIdle {
			assertEquals(
				RuleMatcher(name = "session", match = "checked-value", required = true, matchType = MatchType.EQUAL_TO),
				rules.cookies?.single(),
			)
		}
		required.performClick()
		compose.runOnIdle { assertEquals(false, rules.cookies?.single()?.required) }
		compose.onNode(hasText("Require at least one cookie") and isToggleable()).performClick()
		compose.runOnIdle { assertEquals(true, rules.verifyCookies) }
		compose.onNodeWithText("Delete cookie").performScrollTo().assertMinimumTouchTarget()
		captureEvidence("cookie-table-rtl-large")
		compose.onNodeWithText("Delete cookie").performClick()
		compose.runOnIdle { assertNull(rules.cookies) }
	}

	@Test
	fun `criteria tab edits request header, query and body rules`() {
		var rules by mutableStateOf(RequestRules())
		compose.setContent {
			Column(Modifier.verticalScroll(rememberScrollState())) {
				CriteriaTab(
					variant = ProjectVariant(name = "Success", status = 200),
					requestRules = rules,
					auth = null,
					network = null,
					strictCallCount = null,
					onUpdateVariant = {},
					onUpdate = { rules = it },
					onUpdateAuth = {},
					onUpdateNetwork = {},
					onUpdateStrictCallCount = {},
				)
			}
		}
		compose.onAllNodesWithText("Add")[0].performScrollTo().performClick()
		field("Header Name").performScrollTo().replaceTextVerified("X-Api-Version")
		compose.onNode(hasText("Require a request body") and isToggleable()).performScrollTo().performClick()
		compose.onAllNodesWithText("Add")[2].performScrollTo().performClick()
		field("Content type").performScrollTo().replaceTextVerified("application/json")
		compose.runOnIdle {
			assertEquals(
				RequestRules(
					headers = listOf(RuleMatcher(name = "X-Api-Version", required = true, matchType = MatchType.REQUIRE)),
					requiresBody = true,
					acceptedContentTypes = listOf("application/json"),
				),
				rules,
			)
		}
		captureEvidence("criteria-request-validation")
	}

	@Test
	fun `body encoding switch converts the body and refuses binary data`() {
		var variant by mutableStateOf(
			ProjectVariant(
				name = "Text",
				status = 200,
				headers = mapOf("Content-Type" to "text/plain"),
				body = YamlValue.Str("hello"),
			),
		)
		compose.setContent {
			Column {
				BodyTab(
					endpointMethod = "GET",
					endpointPath = "/avatar",
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
		compose.onNodeWithText("Base64").performClick()
		compose.runOnIdle {
			assertEquals(YamlValue.Str("aGVsbG8="), variant.body)
			assertEquals(BodyEncoding.BASE64, variant.bodyEncoding)
		}
		compose.runOnIdle { variant = variant.copy(body = YamlValue.Str("//4=")) }
		compose.onNodeWithText("UTF-8 text").performClick()
		compose.onNodeWithText("This body is binary data, not UTF-8 text, so it has to stay base64.").assertExists()
		compose.runOnIdle { assertEquals(BodyEncoding.BASE64, variant.bodyEncoding) }
		captureEvidence("body-encoding-binary")
	}

	@Test
	fun `stream settings turn on with defaults and allow a disconnect at zero bytes`() {
		var variant by mutableStateOf(ProjectVariant(name = "Slow", status = 200))
		compose.setContent { Column { StreamSettingsSection(variant) { variant = it } } }
		compose.onNode(hasText("Stream the response in chunks") and isToggleable()).performClick()
		compose.runOnIdle { assertEquals(defaultResponseStream(), variant.stream) }
		field("Chunk (bytes)").replaceTextVerified("5")
		field("Disconnect after (bytes)").replaceTextVerified("0")
		compose.runOnIdle {
			assertEquals(ResponseStream(chunkBytes = 5, intervalMs = 100, disconnectAfterBytes = 0), variant.stream)
		}
		field("Disconnect after (bytes)").replaceTextVerified("")
		compose.runOnIdle { assertNull(variant.stream?.disconnectAfterBytes) }
		captureEvidence("stream-settings")
		compose.onNode(hasText("Stream the response in chunks") and isToggleable()).performClick()
		compose.runOnIdle { assertNull(variant.stream) }
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
		replaceBody("""{"count":2}""")
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(1))), variant.body) }
		compose.waitForContentDescription("Save body edit").performClick()
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(2))), variant.body) }
		compose.waitForContentDescription("Edit body").performClick()
		replaceBody("""{"count":3}""")
		compose.waitForContentDescription("Cancel body edit").performClick()
		compose.runOnIdle { assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(2))), variant.body) }
		captureEvidence("body-shared-actions-light")
	}

	private fun replaceBody(text: String) {
		compose.onNode(hasSetTextAction()).performClick().performKeyInput {
			withKeyDown(if (System.getProperty("os.name").contains("Mac")) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.A) }
		}
		compose.onNode(hasSetTextAction()).performTextInput(text)
	}

	private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

	private fun chooseCondition(from: String, to: String) {
		compose.onNodeWithText(from).performScrollTo().performClick()
		compose.onAllNodesWithText(to).onLast().performClick()
		compose.waitForIdle()
	}

	private fun captureEvidence(name: String) {
		val destination = File("build/reports/ui-integration/$name.png")
		destination.parentFile.mkdirs()
		destination.writeBytes(compose.onAllNodes(isRoot())[0].captureToPng())
	}
}
