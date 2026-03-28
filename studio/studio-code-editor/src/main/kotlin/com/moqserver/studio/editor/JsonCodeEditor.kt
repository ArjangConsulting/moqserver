package com.moqserver.studio.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
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
) {
    SwingPanel(
        modifier = modifier,
        factory = { JsonEditorPanel(text, onTextChange, syntaxStyle, readOnly) },
        update = { panel -> panel.setTextIfDifferent(text) }
    )
}

private class JsonEditorPanel(
    initialText: String,
    onTextChange: (String) -> Unit,
    syntaxStyle: String,
    readOnly: Boolean,
) : JPanel(BorderLayout()) {
    private val textArea = RSyntaxTextArea(20, 80).apply {
        text = initialText
        syntaxEditingStyle = syntaxStyle
        isEditable = !readOnly
        isCodeFoldingEnabled = true
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
}