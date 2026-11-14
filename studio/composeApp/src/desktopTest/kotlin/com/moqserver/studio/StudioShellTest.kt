package com.moqserver.studio

import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.ThemePreference
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.projectformat.format.FormatServiceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioShellTest {
	@Test
	fun `import file kinds keep the dialog titles, extensions and failure messages`() {
		assertEquals("OpenAPI Spec", ImportFileKind.OPENAPI.label)
		assertEquals(setOf("har", "json"), ImportFileKind.HAR.extensions)
		assertEquals(ImportSourceType.OPENAPI, ImportFileKind.SWAGGER.sourceType)
		assertEquals("Failed to parse Swagger spec", ImportFileKind.SWAGGER.failureContext)
		assertEquals("Failed to parse HAR file", ImportFileKind.HAR.failureContext)
	}

	@Test
	fun `URL import errors distinguish fetch failures from parse failures`() {
		val fetch = urlImportErrorMessage(FormatServiceException("E_IMPORT_FETCH_FAILED", "HTTP 404"))
		assertEquals("HTTP 404", fetch)
		val parse = urlImportErrorMessage(FormatServiceException("E_IMPORT_PARSE_FAILED", "bad yaml"))
		assertTrue(parse.orEmpty().startsWith("Successfully fetched content"))
		assertTrue(parse.orEmpty().endsWith("bad yaml"))
		val other = urlImportErrorMessage(IllegalStateException("boom"))
		assertTrue(other.orEmpty().endsWith("boom"))
	}

	@Test
	fun `app state starts from file settings and tracks pending opens`() {
		val state = StudioAppState(AISettings(themeMode = ThemePreference.DARK), initialProjectPath = "/a.moqproj")
		assertEquals(StudioThemeMode.DARK, state.themeMode)
		assertFalse(state.credentialsLoaded)
		assertEquals(0, state.pendingProjectOpenToken)

		state.requestProjectOpen("/b.moqproj")
		state.requestProjectOpen("/b.moqproj")

		assertEquals("/b.moqproj", state.pendingProjectOpenPath)
		assertEquals(2, state.pendingProjectOpenToken)
	}

	@Test
	fun `dismissing the URL dialog clears its draft`() {
		val state = StudioAppState(AISettings(), initialProjectPath = null)
		state.importURLState = ImportFromURLState(url = "https://example.com/openapi.yaml")
		state.showImportURLDialog = true

		state.dismissImportURLDialog()

		assertFalse(state.showImportURLDialog)
		assertEquals("", state.importURLState.url)
		assertNull(state.importURLState.error)
	}

	@Test
	fun `theme preferences round-trip through the studio theme mode`() {
		ThemePreference.entries.forEach { preference ->
			assertEquals(preference, preference.toStudioThemeMode().toThemePreference())
		}
	}
}
