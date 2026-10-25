package com.moqserver.studio.data

import io.github.maniramezan.testcommons.TemporaryDirectory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportHistoryRepositoryTest {
	private val temporaryDirectory = TemporaryDirectory("import-history-test")

	@AfterTest
	fun tearDown() {
		temporaryDirectory.remove()
	}

	private fun makeRepo(): Pair<ImportHistoryRepository, File> {
		val dir = File(temporaryDirectory.file, "history")
		return ImportHistoryRepository(historyDir = dir) to dir
	}

	// ---------- loadDeselected ----------

	@Test
	fun `loadDeselected returns empty set when no history file exists`() {
		val (repo, _) = makeRepo()
		val result = repo.loadDeselected("/tmp/myproject.moqproj")
		assertEquals(emptySet(), result)
	}

	@Test
	fun `loadDeselected returns saved IDs for the given project path`() {
		val (repo, _) = makeRepo()
		val ids = setOf("get-users", "post-items")
		repo.saveDeselected("/tmp/myproject.moqproj", ids)

		val result = repo.loadDeselected("/tmp/myproject.moqproj")

		assertEquals(ids, result)
	}

	@Test
	fun `loadDeselected returns empty set when file content is corrupt`() {
		val (repo, dir) = makeRepo()
		// Write garbage into the slot that the repo would use for this path
		val safeId = "/tmp/myproject.moqproj"
			.replace(Regex("[^a-zA-Z0-9._-]"), "_")
			.trimStart('_')
			.take(200)
		File(dir, "$safeId.json").also {
			dir.mkdirs()
			it.writeText("{ not valid json !!!")
		}

		val result = repo.loadDeselected("/tmp/myproject.moqproj")

		assertEquals(emptySet(), result)
	}

	// ---------- saveDeselected ----------

	@Test
	fun `saveDeselected persists IDs and round-trips through loadDeselected`() {
		val (repo, _) = makeRepo()
		val ids = setOf("delete-item-param", "patch-users-param")

		repo.saveDeselected("/tmp/project.moqproj", ids)
		val loaded = repo.loadDeselected("/tmp/project.moqproj")

		assertEquals(ids, loaded)
	}

	@Test
	fun `saveDeselected with empty set deletes existing file`() {
		val (repo, dir) = makeRepo()
		repo.saveDeselected("/tmp/project.moqproj", setOf("get-items"))
		assertTrue(dir.listFiles()?.isNotEmpty() == true, "File should exist after save")

		repo.saveDeselected("/tmp/project.moqproj", emptySet())

		assertFalse(dir.listFiles()?.any { it.name.endsWith(".json") } == true, "File should be deleted when set is empty")
	}

	@Test
	fun `saveDeselected creates parent directory if it does not exist`() {
		val base = temporaryDirectory.file
		val nestedDir = File(base, "nested/subdir")
		assertFalse(nestedDir.exists())

		val repo = ImportHistoryRepository(historyDir = nestedDir)
		repo.saveDeselected("/tmp/proj.moqproj", setOf("get-items"))

		assertTrue(nestedDir.exists(), "Directory should be created by save")
	}

	@Test
	fun `saveDeselected stores IDs in sorted order`() {
		val (repo, dir) = makeRepo()
		val ids = setOf("z-endpoint", "a-endpoint", "m-endpoint")

		repo.saveDeselected("/tmp/project.moqproj", ids)

		// Re-load the raw file to check sort order
		val safeId = "/tmp/project.moqproj"
			.replace(Regex("[^a-zA-Z0-9._-]"), "_")
			.trimStart('_')
			.take(200)
		val content = File(dir, "$safeId.json").readText()
		val aIndex = content.indexOf("a-endpoint")
		val mIndex = content.indexOf("m-endpoint")
		val zIndex = content.indexOf("z-endpoint")
		assertTrue(aIndex < mIndex && mIndex < zIndex, "IDs should be stored in sorted order")
	}

	// ---------- project path isolation ----------

	@Test
	fun `different project paths use separate history files`() {
		val (repo, _) = makeRepo()

		repo.saveDeselected("/tmp/project-a.moqproj", setOf("get-items"))
		repo.saveDeselected("/tmp/project-b.moqproj", setOf("post-users"))

		assertEquals(setOf("get-items"), repo.loadDeselected("/tmp/project-a.moqproj"))
		assertEquals(setOf("post-users"), repo.loadDeselected("/tmp/project-b.moqproj"))
	}

	// ---------- path slug edge cases ----------

	@Test
	fun `loadDeselected handles paths with special characters without throwing`() {
		val (repo, _) = makeRepo()
		// Paths with spaces, colons, slashes — all should produce a safe file name
		val result = repo.loadDeselected("/Users/a b/my project: (dev).moqproj")
		assertEquals(emptySet(), result)
	}

	@Test
	fun `loadDeselected with empty path string does not throw`() {
		val (repo, _) = makeRepo()
		val result = repo.loadDeselected("")
		assertEquals(emptySet(), result)
	}
}
