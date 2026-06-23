package com.moqserver.studio.editor

import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonEditorPanelTest {

	private val changes = mutableListOf<String>()
	private lateinit var panel: JsonEditorPanel

	@BeforeTest
	fun setUp() {
		System.setProperty("java.awt.headless", "true")
		changes.clear()
		panel = JsonEditorPanel(
			initialText = "{\"a\":1}",
			onTextChange = { changes.add(it) },
			syntaxStyle = SyntaxConstants.SYNTAX_STYLE_JSON,
			readOnly = false,
			backgroundColor = Color.BLACK,
			foregroundColor = Color.WHITE,
		)
	}

	@AfterTest
	fun tearDown() {
		changes.clear()
	}

	@Test
	fun `setTextIfDifferent only replaces when text differs`() {
		val before = changes.size
		panel.setTextIfDifferent("{\"a\":1}")
		assertEquals(before, changes.size, "identical text should not trigger a document change")

		panel.setTextIfDifferent("{\"a\":2}")
		assertTrue(changes.isNotEmpty(), "changed text should propagate through the document listener")
		assertEquals("{\"a\":2}", changes.last())
	}

	@Test
	fun `setReadOnly toggles editability`() {
		panel.setReadOnly(true)
		assertFalse(panel.textArea.isEditable)
		panel.setReadOnly(false)
		assertTrue(panel.textArea.isEditable)
	}

	@Test
	fun `setColors applies background and foreground`() {
		panel.setColors(Color.DARK_GRAY, Color.LIGHT_GRAY)
		assertEquals(Color.DARK_GRAY, panel.textArea.background)
		assertEquals(Color.LIGHT_GRAY, panel.textArea.foreground)
	}
}
