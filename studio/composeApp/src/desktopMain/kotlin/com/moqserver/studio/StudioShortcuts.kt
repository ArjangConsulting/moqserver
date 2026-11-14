package com.moqserver.studio

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Menu shortcuts for the host platform: Command on macOS, Control elsewhere. */
internal class StudioShortcuts(private val isMac: Boolean) {
	val save = if (isMac) KeyShortcut(Key.S, meta = true) else KeyShortcut(Key.S, ctrl = true)
	val saveAs = if (isMac) KeyShortcut(Key.S, meta = true, alt = true) else KeyShortcut(Key.S, ctrl = true, alt = true)
	val undo = if (isMac) KeyShortcut(Key.Z, meta = true) else KeyShortcut(Key.Z, ctrl = true)
	val redo = if (isMac) KeyShortcut(Key.Z, meta = true, shift = true) else KeyShortcut(Key.Y, ctrl = true)

	/** macOS shows Preferences in the application menu, so the Edit menu only offers it elsewhere. */
	val showsPreferencesInMenuBar = !isMac

	fun isUndo(event: KeyEvent): Boolean {
		if (event.type != KeyEventType.KeyDown || event.key != Key.Z || event.isShiftPressed) return false
		return if (isMac) event.isMetaPressed else event.isCtrlPressed
	}

	fun isRedo(event: KeyEvent): Boolean {
		if (event.type != KeyEventType.KeyDown) return false
		return if (isMac) {
			event.isMetaPressed && event.isShiftPressed && event.key == Key.Z
		} else {
			event.isCtrlPressed && event.key == Key.Y
		}
	}

	fun isPreferences(event: KeyEvent): Boolean =
		isMac && event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.Comma
}
