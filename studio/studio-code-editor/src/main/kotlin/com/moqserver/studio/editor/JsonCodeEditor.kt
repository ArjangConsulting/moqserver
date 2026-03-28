package com.moqserver.studio.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.Color
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane

@Composable
fun JsonCodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    syntaxStyle: String = SyntaxConstants.SYNTAX_STYLE_JSON,
    backgroundColor: Color = Color(0x1E, 0x1E, 0x1E),
    foregroundColor: Color = Color(0xE6, 0xED, 0xF3),
) {
    SwingPanel(
        modifier = modifier,
        factory = { JsonEditorPanel(text, onTextChange, syntaxStyle, readOnly, backgroundColor, foregroundColor) },
        update = { panel ->
            panel.setTextIfDifferent(text)
            panel.setReadOnly(readOnly)
            panel.setColors(backgroundColor, foregroundColor)
        }
    )
}

private class JsonEditorPanel(
    initialText: String,
    onTextChange: (String) -> Unit,
    syntaxStyle: String,
    readOnly: Boolean,
    backgroundColor: Color,
    foregroundColor: Color,
) : JPanel(BorderLayout()) {
    private val textArea = RSyntaxTextArea(20, 80).apply {
        text = initialText
        syntaxEditingStyle = syntaxStyle
        isEditable = !readOnly
        isCodeFoldingEnabled = true
        background = backgroundColor
        foreground = foregroundColor
        currentLineHighlightColor = backgroundColor.brighter()
        caretColor = foregroundColor
        selectedTextColor = foregroundColor
        selectionColor = backgroundColor.brighter().brighter()
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent?) {
                onTextChange(text)
            }

            override fun removeUpdate(event: DocumentEvent?) {
                onTextChange(text)
            }

            override fun changedUpdate(event: DocumentEvent?) {
                onTextChange(text)
            }
        })
    }

    init {
        add(RTextScrollPane(textArea), BorderLayout.CENTER)
    }

    fun setTextIfDifferent(newText: String) {
        if (textArea.text != newText) {
            textArea.text = newText
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        textArea.isEditable = !readOnly
    }

    fun setColors(backgroundColor: Color, foregroundColor: Color) {
        textArea.background = backgroundColor
        textArea.foreground = foregroundColor
        textArea.currentLineHighlightColor = backgroundColor.brighter()
        textArea.caretColor = foregroundColor
        textArea.selectedTextColor = foregroundColor
        textArea.selectionColor = backgroundColor.brighter().brighter()
    }
}
