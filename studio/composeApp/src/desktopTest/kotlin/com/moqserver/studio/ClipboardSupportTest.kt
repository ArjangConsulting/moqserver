package com.moqserver.studio

import androidx.compose.ui.test.performClick
import io.github.maniramezan.kmpcomponents.CopyButton
import io.github.maniramezan.kmpcomponents.CopyButtonLabels
import io.github.maniramezan.testcommons.compose.ThemedComposeRule
import io.github.maniramezan.testcommons.compose.waitForContentDescription
import org.junit.Rule
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardSupportTest {
	@get:Rule
	val compose = ThemedComposeRule(theme = { StudioTheme(content = it) })

	@Test
	fun `the shared copy button writes plain text to the system clipboard`() {
		val expected = "{\"ok\":true}"
		compose.setContent {
			CopyButton(text = expected, labels = CopyButtonLabels(copy = "Copy response body", copied = "Body copied"))
		}
		compose.waitForContentDescription("Copy response body").performClick()
		compose.waitForIdle()
		assertEquals(expected, Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor))
	}
}
