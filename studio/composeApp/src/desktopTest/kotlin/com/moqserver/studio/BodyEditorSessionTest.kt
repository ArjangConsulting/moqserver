package com.moqserver.studio

import com.arjangconsulting.codeeditor.DocumentSnapshot
import com.arjangconsulting.codeeditor.FormatResult
import com.arjangconsulting.codeeditor.FormattingOptions
import com.arjangconsulting.codeeditor.JsonLanguageService
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.TextEdit
import com.moqserver.studio.endpointdetail.BodyEditorSession
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyEditorSessionTest {
	private val variant = ProjectVariant(name = "Success", status = 200, bodyFile = "fixtures/response.json")

	@Test
	fun `cancel discards draft and undo while unchanged save retains fixture`() {
		val source = """{"count":1}"""
		val session = BodyEditorSession(source)
		session.start()
		session.state.setText("""{"count":2}""")
		session.cancel()
		assertEquals(source, session.state.text)
		assertFalse(session.state.canUndo)
		assertFalse(session.isEditing)
		session.start()
		assertEquals(variant, session.save(variant, isJson = true))
	}

	@Test
	fun `invalid JSON blocks save and changed fixture becomes an inline body`() = runBlocking {
		val session = BodyEditorSession("{}")
		session.start()
		session.state.setText("{broken}")
		assertNull(session.save(variant, isJson = true))
		assertTrue(session.isEditing)
		assertNotNull(session.validationError)
		assertTrue(JsonLanguageService().diagnostics(session.state.snapshot()).isNotEmpty())
		assertEquals(FormatResult.UNAVAILABLE, session.format(JsonLanguageService()))
		assertEquals("{broken}", session.state.text)
		session.state.setText("""{"count":2}""")
		val saved = assertNotNull(session.save(variant, isJson = true))
		assertNull(saved.bodyFile)
		assertEquals(YamlValue.Obj(mapOf("count" to YamlValue.Int(2))), saved.body)
		assertFalse(session.isEditing)
	}

	@Test
	fun `large minified JSON formats and undoes with exact source preservation`() = runBlocking {
		val source = """{"escaped":"\u65e5\n","long":"${"a".repeat(50000)}TAIL","number":1.000e+20}"""
		val session = BodyEditorSession(source)
		session.start()
		assertEquals(FormatResult.APPLIED, session.format(JsonLanguageService()))
		assertTrue(session.state.text.contains("a".repeat(50000) + "TAIL"))
		assertTrue(session.state.text.contains("1.000e+20"))
		assertTrue(session.state.text.contains("\\u65e5\\n"))
		assertTrue(session.state.undo())
		assertEquals(source, session.state.text)
		assertFalse(session.state.canUndo)
	}

	@Test
	fun `cancel stops pending formatting even when source is unchanged`() = runBlocking {
		val started = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		val service = object : LanguageService {
			override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit>? {
				started.complete(Unit)
				finish.await()
				return JsonLanguageService().format(doc, options)
			}
		}
		val source = """{"count":1}"""
		val session = BodyEditorSession(source)
		session.start()
		val result = async { session.format(service) }
		started.await()
		session.cancel()
		session.start()
		finish.complete(Unit)
		result.join()
		assertTrue(result.isCancelled)
		assertEquals(source, session.state.text)
		assertFalse(session.state.canUndo)
	}

	@Test
	fun `raw save preserves long strings exactly`() {
		val source = "日本\r\n" + "x".repeat(50000) + "TAIL"
		val session = BodyEditorSession("")
		session.start()
		session.state.setText(source)
		val saved = assertNotNull(session.save(variant, isJson = false))
		assertEquals(YamlValue.Str(source), saved.body)
		assertNull(saved.bodyFile)
	}

	@Test
	fun `late formatting cannot overwrite newer edits`() = runBlocking {
		val started = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		val service = object : LanguageService {
			override suspend fun format(doc: DocumentSnapshot, options: FormattingOptions): List<TextEdit>? {
				started.complete(Unit)
				finish.await()
				return JsonLanguageService().format(doc, options)
			}
		}
		val session = BodyEditorSession("""{"count":1}""")
		session.start()
		val result = async { session.format(service) }
		started.await()
		session.state.setText("""{"count":2}""")
		finish.complete(Unit)
		assertEquals(FormatResult.STALE, result.await())
		assertEquals("""{"count":2}""", session.state.text)
	}
}
