package com.moqserver.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.moqserver.studio.endpointdetail.BodyTab
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.captureToPng
import io.github.maniramezan.testcommons.compose.waitForContentDescription
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyEditorIntegrationTest {
	private var theme by mutableStateOf(StudioThemeMode.LIGHT)
	private var width by mutableStateOf(1000.dp)
	private var document by mutableStateOf("/first")
	private var variant by mutableStateOf(
		ProjectVariant(
			name = "Success",
			status = 200,
			headers = mapOf("Content-Type" to "application/json"),
			bodyFile = "fixtures/body.json",
		),
	)
	private var showOverlay by mutableStateOf(false)
	private val shortcutKey = if (System.getProperty("os.name").contains("Mac")) Key.MetaLeft else Key.CtrlLeft
	private val source = """{"value":"${"x".repeat(16000)}TAIL"}"""
	private var updates = 0

	@get:Rule
	val compose = ThemedComposeRule(theme = { content ->
		StudioTheme(themeMode = theme) { Surface(content = content) }
	})

	@Test
	fun `endpoint and variant switching and external updates discard draft and undo history`() {
		showBody()
		edit("""{"draft":1}""")
		compose.runOnIdle { document = "/second" }
		compose.waitForContentDescription("Edit body").performClick()
		undo()
		save()
		compose.runOnIdle {
			assertEquals(0, updates)
			assertEquals("fixtures/body.json", variant.bodyFile)
		}
		edit("""{"variantDraft":1}""")
		compose.runOnIdle { variant = variant.copy(name = "Other") }
		compose.waitForContentDescription("Edit body").performClick()
		undo()
		save()
		compose.runOnIdle {
			assertEquals(0, updates)
			document = "/first"
		}
		edit("""{"draft":2}""")
		compose.runOnIdle { variant = variant.copy(bodyFile = null, body = YamlValue.Obj(mapOf("ai" to YamlValue.Bool(true)))) }
		compose.waitForContentDescription("Edit body").performClick()
		undo()
		save()
		compose.runOnIdle {
			assertEquals(0, updates)
			assertEquals(YamlValue.Obj(mapOf("ai" to YamlValue.Bool(true))), variant.body)
		}
	}

	@Test
	fun `tab and reverse tab leave and return to body editor`() {
		showBody()
		compose.waitForContentDescription("Edit body").performClick()
		compose.onNode(hasSetTextAction()).performClick().performKeyInput { pressKey(Key.Tab) }
		compose.onNodeWithText("After body").assertIsFocused().performKeyInput {
			withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
		}
		compose.onNode(hasSetTextAction()).assertIsFocused()
	}

	@Test
	fun `format undo invalid diagnostics selection resizing themes and overlay preserve fixture`() {
		showBody()
		compose.waitForContentDescription("Edit body").performClick()
		compose.onNode(hasSetTextAction()).performClick().performKeyInput {
			withKeyDown(shortcutKey) {
				pressKey(
					if (shortcutKey == Key.MetaLeft) Key.DirectionDown else Key.MoveEnd,
				)
			}
		}
		val mirroredText = editorText()
		assertTrue(
			mirroredText.endsWith("TAIL\"}"),
		)
		capture("body-light-long-tail")
		compose.onNodeWithText("Format JSON").performClick()
		compose.waitUntil(5000) {
			editorText().contains("\n")
		}
		undo()
		save()
		compose.runOnIdle { assertEquals(0, updates) }
		edit("""{"value": broken}""")
		compose.onNodeWithText("Validate JSON").performClick()
		compose.waitForIdle()
		capture("body-light-diagnostics")
		compose.runOnIdle {
			theme = StudioThemeMode.DARK
			width = 500.dp
		}
		compose.onNode(hasSetTextAction()).performClick().performKeyInput {
			withKeyDown(shortcutKey) { pressKey(Key.A) }
		}
		capture("body-dark-narrow-selection")
		compose.runOnIdle { showOverlay = true }
		compose.onNodeWithText("Overlay probe").assertExists()
		capture("body-dark-overlay")
		compose.runOnIdle { showOverlay = false }
		compose.waitForContentDescription("Cancel body edit").performClick()
		compose.runOnIdle { assertEquals(0, updates) }
	}

	private fun showBody() {
		compose.setContent {
			Column(Modifier.width(width)) {
				BodyTab(
					endpointMethod = "GET", endpointPath = document, variant = variant, aiProviderLabel = null,
					canGenerateWithAi = false, isGeneratingWithAi = false, generationError = null, onGenerateBody = {},
					readFixture = { _, _ -> source }, onUpdate = {
						updates++
						variant = it
					},
				)
				Button(onClick = {}) { Text("After body") }
			}
			if (showOverlay) AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text("Overlay probe") })
		}
	}

	private fun edit(text: String) {
		compose.waitForContentDescription("Edit body").performClick()
		compose.onNode(hasSetTextAction()).performClick().performKeyInput { withKeyDown(shortcutKey) { pressKey(Key.A) } }
		compose.onNode(hasSetTextAction()).performTextInput(text)
	}

	private fun save() { compose.waitForContentDescription("Save body edit").performClick() }

	private fun undo() {
		compose.onNode(hasSetTextAction()).requestFocus()
		compose.onNode(hasSetTextAction()).assertIsFocused().performKeyInput { withKeyDown(shortcutKey) { pressKey(Key.Z) } }
	}

	private fun editorText(): String {
		val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
		return input.config[SemanticsProperties.EditableText].text
	}

	private fun capture(name: String) {
		val destination = File("build/reports/ui-integration/$name.png")
		destination.parentFile.mkdirs()
		val roots = compose.onAllNodes(isRoot())
		val index = if (name.endsWith("overlay")) roots.fetchSemanticsNodes().lastIndex else 0
		destination.writeBytes(roots[index].captureToPng())
	}
}
