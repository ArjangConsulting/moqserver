package com.moqserver.studio.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.moqserver.studio.designsystem.StudioColors
import com.moqserver.studio.designsystem.toAwtColor
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Composable
fun JsonCodeEditor(
	text: String,
	onTextChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	readOnly: Boolean = false,
	syntaxStyle: String = SyntaxConstants.SYNTAX_STYLE_JSON,
	backgroundColor: Color = StudioColors.codeEditorBackground.toAwtColor(),
	foregroundColor: Color = StudioColors.codeEditorForeground.toAwtColor(),
) {
	SwingPanel(
		modifier = modifier,
		factory = { JsonEditorPanel(text, onTextChange, syntaxStyle, readOnly, backgroundColor, foregroundColor) },
		update = { panel ->
			panel.setTextIfDifferent(text)
			panel.setReadOnly(readOnly)
			panel.setColors(backgroundColor, foregroundColor)
		},
	)
}

internal class JsonEditorPanel(
	initialText: String,
	onTextChange: (String) -> Unit,
	syntaxStyle: String,
	readOnly: Boolean,
	backgroundColor: Color,
	foregroundColor: Color,
) : JPanel(BorderLayout()) {
	internal val textArea = RSyntaxTextArea(20, 80).apply {
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
