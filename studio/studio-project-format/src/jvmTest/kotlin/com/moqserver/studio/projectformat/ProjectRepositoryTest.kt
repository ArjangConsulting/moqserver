package com.moqserver.studio.projectformat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRepositoryTest {

    private val sampleProjectPath = findSampleProject()
    private val repo = ProjectRepository()

    private fun findSampleProject(): String {
        var current: File? = File(System.getProperty("user.dir")).canonicalFile
        while (current != null) {
            val sample = File(current, "server/Tests/MoqFormatTests/Fixtures/sample-app.moqproj")
            if (sample.isDirectory) {
                return sample.canonicalPath
            }
            current = current.parentFile
        }
        error("Cannot find sample-app.moqproj from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `load sample project - manifest`() {
        val project = repo.load(sampleProjectPath)

        assertEquals("1", project.manifest.version)
        assertEquals("Sample App API Mock", project.manifest.name)
        assertNotNull(project.manifest.description)
        assertEquals(0, project.manifest.defaults.delayMs)
        assertEquals(AuthType.NONE, project.manifest.defaults.auth.type)
        assertEquals(false, project.manifest.defaults.auth.verify)
        assertNull(project.manifest.defaults.auth.headerName)
        assertEquals(0, project.manifest.defaults.network.latencyMs)
        assertNotNull(project.manifest.globalRules)
        assertTrue(project.manifest.globalRules!!.requiredHeaders.isNullOrEmpty())
        assertEquals(false, project.manifest.globalRules!!.verifyCookies)
    }

    @Test
    fun `load sample project - endpoints count`() {
        val project = repo.load(sampleProjectPath)
        assertEquals(3, project.endpoints.size)
    }

    @Test
    fun `load sample project - list-users endpoint`() {
        val project = repo.load(sampleProjectPath)
        val ep = project.endpoints.find { it.id == "list-users" }!!

        assertEquals("List Users", ep.alias)
        assertEquals("GET", ep.method)
        assertEquals("/api/v1/users", ep.path)
        assertEquals(listOf("users", "core"), ep.tags)
        assertEquals(AuthType.BEARER, ep.auth!!.type)
        assertEquals(true, ep.auth!!.verify)
        assertEquals(4, ep.variants.size)

        val success = ep.variants.first()
        assertEquals("success", success.name)
        assertEquals(true, success.isDefault)
        assertEquals(200, success.status)
        assertEquals("fixtures/users-list.json", success.bodyFile)
        assertEquals(50, success.delayMs)

        val empty = ep.variants[1]
        assertNotNull(empty.body)

        assertNotNull(ep.network)
        assertEquals(100, ep.network!!.latencyMs)
        assertEquals(20, ep.network!!.jitterMs)
    }

    @Test
    fun `load sample project - graphql with document`() {
        val project = repo.load(sampleProjectPath)
        val ep = project.endpoints.find { it.id == "current-user" }!!

        assertEquals("POST", ep.method)
        assertEquals("/graphql", ep.path)
        assertNotNull(ep.operation)
        assertEquals(OperationType.QUERY, ep.operation!!.type)
        assertNotNull(ep.operation!!.document)
        assertTrue(ep.operation!!.document!!.contains("currentUser"))
    }

    @Test
    fun `load sample project - graphql with named operation`() {
        val project = repo.load(sampleProjectPath)
        val ep = project.endpoints.find { it.id == "get-user-profile" }!!

        assertNotNull(ep.operation)
        assertEquals(OperationType.QUERY, ep.operation!!.type)
        assertEquals("GetUserProfile", ep.operation!!.name)
        assertNull(ep.operation!!.document)
    }

    @Test
    fun `round-trip - save and reload produces same data`() {
        val project = repo.load(sampleProjectPath)

        val tempDir = kotlin.io.path.createTempDirectory("moqproj-roundtrip").toFile()
        try {
            repo.save(project, tempDir.absolutePath)
            val reloaded = repo.load(tempDir.absolutePath)

            assertEquals(project.manifest.version, reloaded.manifest.version)
            assertEquals(project.manifest.name, reloaded.manifest.name)
            assertEquals(project.manifest.description, reloaded.manifest.description)
            assertEquals(project.manifest.defaults, reloaded.manifest.defaults)
            assertEquals(project.manifest.globalRules, reloaded.manifest.globalRules)
            assertEquals(project.endpoints.size, reloaded.endpoints.size)

            for (ep in project.endpoints) {
                val reloadedEp = reloaded.endpoints.find { it.id == ep.id }
                assertNotNull(reloadedEp, "Missing endpoint ${ep.id} after round-trip")
                assertEquals(ep.method, reloadedEp.method)
                assertEquals(ep.path, reloadedEp.path)
                assertEquals(ep.alias, reloadedEp.alias)
                assertEquals(ep.tags, reloadedEp.tags)
                assertEquals(ep.auth, reloadedEp.auth)
                assertEquals(ep.operation?.type, reloadedEp.operation?.type)
                assertEquals(ep.operation?.name, reloadedEp.operation?.name)
                assertEquals(ep.variants.size, reloadedEp.variants.size)
                assertEquals(ep.network?.latencyMs, reloadedEp.network?.latencyMs)
                assertEquals(ep.network?.jitterMs, reloadedEp.network?.jitterMs)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save removes endpoint files for deleted endpoints`() {
        val project = repo.load(sampleProjectPath)
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-delete-endpoint").toFile()
        try {
            repo.save(project, tempDir.absolutePath)

            val trimmed = project.copy(endpoints = project.endpoints.filterNot { it.id == "current-user" })
            repo.save(trimmed, tempDir.absolutePath)

            assertFalse(File(tempDir, "endpoints/current-user.yml").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save as copies referenced fixtures to new project directory`() {
        val project = repo.load(sampleProjectPath)
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-save-as").toFile()
        try {
            repo.save(project, tempDir.absolutePath)

            val copiedFixture = File(tempDir, "fixtures/users-list.json")
            assertTrue(copiedFixture.isFile)

            val sourceFixture = File(sampleProjectPath, "fixtures/users-list.json")
            assertEquals(sourceFixture.readText(), copiedFixture.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save and reload preserves nested bodies with quoted multiline strings`() {
        val project = repo.load(sampleProjectPath)
        val nestedBody = YamlValue.Obj(
            mapOf(
                "analyzedContent" to YamlValue.Obj(
                    mapOf(
                        "sentences" to YamlValue.Array(
                            listOf(
                                YamlValue.Obj(
                                    mapOf(
                                        "sentence" to YamlValue.Str("He said: \"hello\"\nand left."),
                                        "startTime" to YamlValue.Double(0.13),
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val endpoint = project.endpoints.first().copy(
            id = "nested-body-regression",
            variants = listOf(
                project.endpoints.first().variants.first().copy(
                    bodyFile = null,
                    body = nestedBody,
                )
            ),
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-nested-body").toFile()

        try {
            repo.save(project.copy(endpoints = listOf(endpoint)), tempDir.absolutePath)

            val reloaded = repo.load(tempDir.absolutePath)
            val reloadedBody = reloaded.endpoints.single().variants.single().body

            assertEquals(nestedBody, reloadedBody)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
