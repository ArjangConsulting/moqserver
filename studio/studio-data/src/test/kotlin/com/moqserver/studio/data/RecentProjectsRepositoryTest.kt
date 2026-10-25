package com.moqserver.studio.data

import io.github.maniramezan.testcommons.TemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentProjectsRepositoryTest {
    private val temporaryDirectory = TemporaryDirectory("recent-projects")

    @AfterTest
    fun tearDown() {
        temporaryDirectory.remove()
    }

    @Test
    fun `save stores distinct recent projects in order`() {
        val settingsFile = temporaryDirectory.write("", "settings.json")
        val repository = RecentProjectsRepository(settingsFile = settingsFile)

        repository.save(listOf("/tmp/one.moqproj", "/tmp/two.moqproj", "/tmp/one.moqproj"))

        assertEquals(listOf("/tmp/one.moqproj", "/tmp/two.moqproj"), repository.load())
    }

    @Test
    fun `load trims blanks and limits to ten projects`() {
        val settingsFile = temporaryDirectory.write("", "settings.json")
        val repository = RecentProjectsRepository(settingsFile = settingsFile)
        val projects = (1..12).map { " /tmp/project-$it.moqproj " }

        repository.save(projects + listOf("  "))

        assertEquals((1..10).map { "/tmp/project-$it.moqproj" }, repository.load())
    }
}
