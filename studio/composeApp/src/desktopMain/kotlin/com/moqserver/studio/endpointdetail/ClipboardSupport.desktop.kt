package com.moqserver.studio.endpointdetail

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

internal actual fun copyTextToClipboard(text: String) {
	Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

internal fun readTextFromClipboard(): String? {
	val clipboard = Toolkit.getDefaultToolkit().systemClipboard
	return if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
		clipboard.getData(DataFlavor.stringFlavor) as? String
	} else {
		null
	}
}
