package com.moqserver.studio.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportHistoryRepositoryTest {

	private fun makeRepo(): Pair<ImportHistoryRepository, File> {
		val dir = createTempDir("import-history-test")
		return ImportHistoryRepository(historyDir = dir) to dir
	}

	// ---------- loadDeselected ----------

	@Test
	fun `loadDeselected returns empty set when no history file exists`() {
		val (repo, dir) = makeRepo()
		val result = repo.loadDeselected("/tmp/myproject.moqproj")
		assertEquals(emptySet(), result)
		dir.deleteRecursively()
	}

	@Test
	fun `loadDeselected returns saved IDs for the given project path`() {
		val (repo, dir) = makeRepo()
		val ids = setOf("get-users", "post-items")
		repo.saveDeselected("/tmp/myproject.moqproj", ids)

		val result = repo.loadDeselected("/tmp/myproject.moqproj")

		assertEquals(ids, result)
		dir.deleteRecursively()
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
		dir.deleteRecursively()
	}

	// ---------- saveDeselected ----------

	@Test
	fun `saveDeselected persists IDs and round-trips through loadDeselected`() {
		val (repo, dir) = makeRepo()
		val ids = setOf("delete-item-param", "patch-users-param")

		repo.saveDeselected("/tmp/project.moqproj", ids)
		val loaded = repo.loadDeselected("/tmp/project.moqproj")

		assertEquals(ids, loaded)
		dir.deleteRecursively()
	}

	@Test
	fun `saveDeselected with empty set deletes existing file`() {
		val (repo, dir) = makeRepo()
		repo.saveDeselected("/tmp/project.moqproj", setOf("get-items"))
		assertTrue(dir.listFiles()?.isNotEmpty() == true, "File should exist after save")

		repo.saveDeselected("/tmp/project.moqproj", emptySet())

		assertFalse(dir.listFiles()?.any { it.name.endsWith(".json") } == true, "File should be deleted when set is empty")
		dir.deleteRecursively()
	}

	@Test
	fun `saveDeselected creates parent directory if it does not exist`() {
		val base = createTempDir("import-history-parent")
		val nestedDir = File(base, "nested/subdir")
		assertFalse(nestedDir.exists())

		val repo = ImportHistoryRepository(historyDir = nestedDir)
		repo.saveDeselected("/tmp/proj.moqproj", setOf("get-items"))

		assertTrue(nestedDir.exists(), "Directory should be created by save")
		base.deleteRecursively()
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
		dir.deleteRecursively()
	}

	// ---------- project path isolation ----------

	@Test
	fun `different project paths use separate history files`() {
		val (repo, dir) = makeRepo()

		repo.saveDeselected("/tmp/project-a.moqproj", setOf("get-items"))
		repo.saveDeselected("/tmp/project-b.moqproj", setOf("post-users"))

		assertEquals(setOf("get-items"), repo.loadDeselected("/tmp/project-a.moqproj"))
		assertEquals(setOf("post-users"), repo.loadDeselected("/tmp/project-b.moqproj"))
		dir.deleteRecursively()
	}

	// ---------- path slug edge cases ----------

	@Test
	fun `loadDeselected handles paths with special characters without throwing`() {
		val (repo, dir) = makeRepo()
		// Paths with spaces, colons, slashes — all should produce a safe file name
		val result = repo.loadDeselected("/Users/a b/my project: (dev).moqproj")
		assertEquals(emptySet(), result)
		dir.deleteRecursively()
	}

	@Test
	fun `loadDeselected with empty path string does not throw`() {
		val (repo, dir) = makeRepo()
		val result = repo.loadDeselected("")
		assertEquals(emptySet(), result)
		dir.deleteRecursively()
	}
}

private fun createTempDir(prefix: String): File =
	File.createTempFile(prefix, null).also {
		it.delete()
		it.mkdir()
	}
