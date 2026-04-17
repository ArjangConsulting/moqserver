package com.moqserver.studio.endpointdetail

import org.jetbrains.skiko.ClipboardManager

internal actual fun copyTextToClipboard(text: String) {
	ClipboardManager().setText(text)
}

internal fun readTextFromClipboard(): String? = ClipboardManager().getText()
