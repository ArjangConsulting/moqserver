package com.moqserver.studio

import androidx.compose.ui.graphics.compositeOver
import com.arjangconsulting.codeeditor.CodeEditorSyntaxTheme
import com.arjangconsulting.codeeditor.contrastRatio
import com.moqserver.studio.designsystem.StudioColors
import com.moqserver.studio.endpointdetail.bodyEditorColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyEditorColorsTest {

    @Test
    fun `body panel uses the dark syntax palette`() {
        assertEquals(StudioColors.editorPanelBackground, bodyEditorColors.background)
        assertEquals(CodeEditorSyntaxTheme.Default.dark, bodyEditorColors.syntax)
        assertEquals(emptyList(), bodyEditorColors.syntax.lowContrastKinds(StudioColors.editorPanelBackground))
    }

    @Test
    fun `body panel chrome is readable on the dark panel`() {
        val panel = StudioColors.editorPanelBackground
        assertTrue(contrastRatio(bodyEditorColors.foreground, panel) >= 4.5f)
        assertTrue(contrastRatio(bodyEditorColors.gutterActiveForeground, panel) >= 4.5f)
        assertTrue(contrastRatio(bodyEditorColors.gutterForeground, panel) >= 3f)
        val selection = bodyEditorColors.selectionBackground.compositeOver(panel)
        assertTrue(contrastRatio(bodyEditorColors.foreground, selection) >= 3f)
    }
}
