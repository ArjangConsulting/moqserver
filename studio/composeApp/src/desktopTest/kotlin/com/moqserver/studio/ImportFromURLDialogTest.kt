package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportFromURLDialogTest {

	@Test
	fun `dialog copy uses import labels for OpenAPI import`() {
		val copy = importFromURLDialogCopy(
			ImportFromURLState(
				mode = URLImportMode.OPENAPI,
				action = URLImportAction.IMPORT,
			),
		)

		assertEquals("Import OpenAPI from URL", copy.title)
		assertEquals("Import", copy.submitButton)
	}

	@Test
	fun `dialog copy uses update labels for Swagger update`() {
		val copy = importFromURLDialogCopy(
			ImportFromURLState(
				mode = URLImportMode.SWAGGER,
				action = URLImportAction.UPDATE,
			),
		)

		assertEquals("Update from Swagger URL", copy.title)
		assertEquals("Update", copy.submitButton)
	}
}
