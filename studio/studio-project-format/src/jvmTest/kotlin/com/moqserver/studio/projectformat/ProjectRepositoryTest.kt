package com.moqserver.studio.projectformat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    @Test
    fun `save and reload preserves quoted header values with yaml special characters`() {
        val project = repo.load(sampleProjectPath)
        val endpoint = project.endpoints.first().copy(
            id = "header-quoting-regression",
            variants = listOf(
                project.endpoints.first().variants.first().copy(
                    headers = mapOf(
                        "report-to" to """{"group":"youtube","max_age":2592000,"endpoints":[{"url":"https://csp.withgoogle.com/csp/report-to/youtube"}]}""",
                        "Alt-Svc" to """h3=":443"; ma=2592000,h3-29=":443"; ma=2592000""",
                    ),
                    body = YamlValue.Str("ok"),
                    bodyFile = null,
                )
            ),
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-header-quoting").toFile()

        try {
            repo.save(project.copy(endpoints = listOf(endpoint)), tempDir.absolutePath)

            val reloaded = repo.load(tempDir.absolutePath)
            val reloadedHeaders = reloaded.endpoints.single().variants.single().headers

            assertEquals(endpoint.variants.single().headers, reloadedHeaders)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `load fails when project manifest is missing`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-missing-manifest").toFile()

        try {
            File(tempDir, "endpoints").mkdirs()

            val error = assertFailsWith<IllegalArgumentException> {
                repo.load(tempDir.absolutePath)
            }

            assertTrue(error.message!!.contains("Missing project.yml"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `load fails when endpoints directory is missing`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-missing-endpoints").toFile()

        try {
            File(tempDir, "project.yml").writeText(
                """
                version: "1"
                name: "Missing Endpoints"
                defaults:
                  delay_ms: 0
                  auth:
                    type: none
                    verify: false
                    header_name: null
                  network:
                    latency_ms: 0
                    jitter_ms: 0
                    packet_loss_percent: 0
                """.trimIndent() + "\n"
            )

            val error = assertFailsWith<IllegalArgumentException> {
                repo.load(tempDir.absolutePath)
            }

            assertTrue(error.message!!.contains("Missing endpoints/ directory"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `load fails when endpoints directory has no yaml files`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-no-endpoints").toFile()

        try {
            File(tempDir, "project.yml").writeText(
                """
                version: "1"
                name: "No Endpoints"
                defaults:
                  delay_ms: 0
                  auth:
                    type: none
                    verify: false
                    header_name: null
                  network:
                    latency_ms: 0
                    jitter_ms: 0
                    packet_loss_percent: 0
                """.trimIndent() + "\n"
            )
            File(tempDir, "endpoints").mkdirs()
            File(tempDir, "endpoints/readme.txt").writeText("not an endpoint")

            val error = assertFailsWith<IllegalArgumentException> {
                repo.load(tempDir.absolutePath)
            }

            assertTrue(error.message!!.contains("No endpoint files found"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `load wraps invalid endpoint yaml with file path context`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-invalid-endpoint").toFile()

        try {
            File(tempDir, "project.yml").writeText(
                """
                version: "1"
                name: "Invalid Endpoint"
                defaults:
                  delay_ms: 0
                  auth:
                    type: none
                    verify: false
                    header_name: null
                  network:
                    latency_ms: 0
                    jitter_ms: 0
                    packet_loss_percent: 0
                """.trimIndent() + "\n"
            )

            val endpointsDir = File(tempDir, "endpoints").apply { mkdirs() }
            File(endpointsDir, "broken.yml").writeText(
                """
                id: "broken"
                method: "GET"
                path: "/broken"
                """.trimIndent() + "\n"
            )

            val error = assertFailsWith<IllegalStateException> {
                repo.load(tempDir.absolutePath)
            }

            assertTrue(error.message!!.contains("Invalid endpoint file"))
            assertTrue(error.message!!.contains("broken.yml"))
            assertTrue(error.message!!.contains("Missing required field 'variants'"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `load accepts yaml endpoint extension`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-yaml-extension").toFile()

        try {
            File(tempDir, "project.yml").writeText(
                """
                version: "1"
                name: "Yaml Extension"
                defaults:
                  delay_ms: 0
                  auth:
                    type: none
                    verify: false
                    header_name: null
                  network:
                    latency_ms: 0
                    jitter_ms: 0
                    packet_loss_percent: 0
                """.trimIndent() + "\n"
            )

            val endpointsDir = File(tempDir, "endpoints").apply { mkdirs() }
            File(endpointsDir, "ping.yaml").writeText(
                """
                id: "ping"
                method: "GET"
                path: "/ping"
                variants:
                  - name: "ok"
                    default: true
                    status: 200
                    body:
                      healthy: true
                """.trimIndent() + "\n"
            )

            val project = repo.load(tempDir.absolutePath)

            assertEquals(1, project.endpoints.size)
            assertEquals("ping", project.endpoints.single().id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save removes stale fixtures and nested fixture directories`() {
        val project = repo.load(sampleProjectPath)
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-stale-fixtures").toFile()

        try {
            repo.save(project, tempDir.absolutePath)

            val inlineOnlyProject = project.copy(
                endpoints = project.endpoints.map { endpoint ->
                    endpoint.copy(
                        variants = endpoint.variants.map { variant ->
                            variant.copy(
                                body = variant.body ?: YamlValue.Obj(emptyMap()),
                                bodyFile = null,
                            )
                        }
                    )
                }
            )

            val staleFile = File(tempDir, "fixtures/unused/nested/old.json").apply {
                parentFile.mkdirs()
                writeText("""{"stale":true}""")
            }

            repo.save(inlineOnlyProject, tempDir.absolutePath)

            assertFalse(File(tempDir, "fixtures/users-list.json").exists())
            assertFalse(staleFile.exists())
            assertFalse(File(tempDir, "fixtures/unused/nested").exists())
            assertFalse(File(tempDir, "fixtures/unused").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save in place keeps referenced fixtures intact`() {
        val sourceDir = kotlin.io.path.createTempDirectory("moqproj-save-in-place").toFile()

        try {
            File(sampleProjectPath).copyRecursively(sourceDir, overwrite = true)

            val project = repo.load(sourceDir.absolutePath)
            val fixtureFile = File(sourceDir, "fixtures/users-list.json")
            val before = fixtureFile.readText()

            repo.save(project, sourceDir.absolutePath)

            assertTrue(fixtureFile.isFile)
            assertEquals(before, fixtureFile.readText())
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `readFixture returns contents for existing fixtures and null for missing ones`() {
        assertNotNull(repo.readFixture(sampleProjectPath, "fixtures/users-list.json"))
        assertNull(repo.readFixture(sampleProjectPath, "fixtures/does-not-exist.json"))
    }
}
