package com.moqserver.studio.projectformat

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProjectRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

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
        assertTrue(project.manifest.globalRules.requiredHeaders.isNullOrEmpty())
        assertEquals(false, project.manifest.globalRules.verifyCookies)
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
        assertEquals("listUsers", ep.referenceName)
        assertEquals("GET", ep.method)
        assertEquals("/api/v1/users", ep.path)
        assertEquals(listOf("users", "core"), ep.tags)
        assertEquals(AuthType.BEARER, ep.auth!!.type)
        assertEquals(true, ep.auth.verify)
        assertEquals(4, ep.variants.size)

        val success = ep.variants.first()
        assertEquals("success", success.name)
        assertEquals("success", success.referenceName)
        assertEquals(true, success.isDefault)
        assertEquals(200, success.status)
        assertEquals("fixtures/users-list.json", success.bodyFile)
        assertEquals(50, success.delayMs)

        val empty = ep.variants[1]
        assertNotNull(empty.body)

        assertNotNull(ep.network)
        assertEquals(100, ep.network.latencyMs)
        assertEquals(20, ep.network.jitterMs)
    }

    @Test
    fun `load sample project - graphql with document`() {
        val project = repo.load(sampleProjectPath)
        val ep = project.endpoints.find { it.id == "current-user" }!!

        assertEquals("POST", ep.method)
        assertEquals("/graphql", ep.path)
        assertNotNull(ep.operation)
        assertEquals(OperationType.QUERY, ep.operation.type)
        assertNotNull(ep.operation.document)
        assertTrue(ep.operation.document.contains("currentUser"))
    }

    @Test
    fun `load sample project - graphql with named operation`() {
        val project = repo.load(sampleProjectPath)
        val ep = project.endpoints.find { it.id == "get-user-profile" }!!

        assertNotNull(ep.operation)
        assertEquals(OperationType.QUERY, ep.operation.type)
        assertEquals("GetUserProfile", ep.operation.name)
        assertNull(ep.operation.document)
    }

    @Test
    fun `load assigns fallback alias when endpoint yaml omits it`() {
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-fallback-alias").toFile()
        try {
            File(tempDir, "project.yml").writeText(
                """
                version: "1"
                name: "Alias Test"

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
            File(tempDir, "fixtures").mkdirs()
            File(endpointsDir, "get-pets.yml").writeText(
                """
                id: "get-pets"
                method: "GET"
                path: "/pets"

                variants:
                  - name: "default"
                    status: 200
                """.trimIndent() + "\n"
            )

            val project = repo.load(tempDir.absolutePath)
            assertEquals("List Pets", project.endpoints.single().alias)
        } finally {
            tempDir.deleteRecursively()
        }
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
                assertEquals(ep.description, reloadedEp.description)
                assertEquals(ep.referenceName, reloadedEp.referenceName)
                assertEquals(ep.tags, reloadedEp.tags)
                assertEquals(ep.auth, reloadedEp.auth)
                assertEquals(ep.operation?.type, reloadedEp.operation?.type)
                assertEquals(ep.operation?.name, reloadedEp.operation?.name)
                assertEquals(ep.variants.size, reloadedEp.variants.size)
                assertEquals(
                    ep.variants.map(ProjectVariant::referenceName),
                    reloadedEp.variants.map(ProjectVariant::referenceName),
                )
                assertEquals(
                    ep.variants.map(ProjectVariant::description),
                    reloadedEp.variants.map(ProjectVariant::description),
                )
                assertEquals(ep.network?.latencyMs, reloadedEp.network?.latencyMs)
                assertEquals(ep.network?.jitterMs, reloadedEp.network?.jitterMs)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `round-trip - save and reload preserves variant descriptions`() {
        val project = MoqProject(
            manifest = ProjectManifest(
                name = "Variant Description Test",
                defaults = ProjectDefaults(
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = listOf(
                EndpointDocument(
                    id = "get-items",
                    method = "GET",
                    path = "/api/items",
                    variants = listOf(
                        ProjectVariant(
                            name = "success",
                            description = "Returns a list of items",
                            isDefault = true,
                            status = 200,
                            body = YamlValue.Str("ok"),
                        ),
                        ProjectVariant(
                            name = "empty",
                            description = null,
                            status = 200,
                            body = YamlValue.Str("[]"),
                        ),
                    ),
                ),
            ),
            projectPath = "/tmp/variant-description-test",
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-variant-description").toFile()

        try {
            repo.save(project, tempDir.absolutePath)

            val yaml = File(tempDir, "endpoints/get-items.yml").readText()
            assertTrue(yaml.contains("description: \"Returns a list of items\""))

            val reloaded = repo.load(tempDir.absolutePath)
            val variants = reloaded.endpoints.single().variants
            assertEquals("Returns a list of items", variants[0].description)
            assertNull(variants[1].description)
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
    fun `save persists fallback alias for endpoints without explicit alias`() {
        val project = MoqProject(
            manifest = ProjectManifest(
                version = "1",
                name = "Alias Test",
                defaults = ProjectDefaults(
                    delayMs = 0,
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = listOf(
                EndpointDocument(
                    id = "delete-pets",
                    method = "DELETE",
                    path = "/pets/{petId}",
                    description = "Deletes the selected pet",
                    variants = listOf(ProjectVariant(name = "default", status = 204)),
                ),
            ),
            projectPath = sampleProjectPath,
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-save-alias").toFile()

        try {
            repo.save(project, tempDir.absolutePath)

            val yaml = File(tempDir, "endpoints/delete-pets.yml").readText()
            assertTrue(yaml.contains("alias: \"Delete Pets By Pet Id\""))
            assertTrue(yaml.contains("description: \"Deletes the selected pet\""))
            assertTrue(yaml.contains("reference_name: \"deletePets\""))
            assertTrue(yaml.contains("reference_name: \"default\""))

            val reloaded = repo.load(tempDir.absolutePath)
            assertEquals("Delete Pets By Pet Id", reloaded.endpoints.single().alias)
            assertEquals("deletePets", reloaded.endpoints.single().referenceName)
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
            val reloadedVariant = reloaded.endpoints.single().variants.single()
            val fixturePath = requireNotNull(reloadedVariant.bodyFile)
            val fixtureText = requireNotNull(repo.readFixture(tempDir.absolutePath, fixturePath))

            assertNull(reloadedVariant.body)
            assertEquals(nestedBody, jsonElementToYamlValue(json.parseToJsonElement(fixtureText)))
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
    fun `save and reload preserves header match type`() {
        val project = repo.load(sampleProjectPath)
        val endpoint = project.endpoints.first().copy(
            id = "header-match-type-regression",
            requestRules = RequestRules(
                headers = listOf(
                    RuleMatcher(
                        name = "X-Request-ID",
                        match = "^req-.*",
                        required = true,
                        matchType = MatchType.MATCHES_REGEX,
                    )
                )
            ),
            variants = listOf(project.endpoints.first().variants.first().copy(body = YamlValue.Str("ok"), bodyFile = null)),
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-header-match-type").toFile()

        try {
            repo.save(project.copy(endpoints = listOf(endpoint)), tempDir.absolutePath)

            val yaml = File(tempDir, "endpoints/header-match-type-regression.yml").readText()
            assertTrue(yaml.contains("match_type: matches_regex"))

            val reloaded = repo.load(tempDir.absolutePath)
            assertEquals(MatchType.MATCHES_REGEX, reloaded.endpoints.single().requestRules?.headers?.single()?.matchType)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save and reload preserves cookies request rules`() {
        val project = repo.load(sampleProjectPath)
        val endpoint = project.endpoints.first().copy(
            id = "cookie-request-rules-regression",
            requestRules = RequestRules(
                cookies = listOf(
                    RuleMatcher(
                        name = "session_id",
                        match = "abc123",
                        required = true,
                        matchType = MatchType.EQUAL_TO,
                    )
                ),
            ),
            variants = listOf(project.endpoints.first().variants.first().copy(body = YamlValue.Str("ok"), bodyFile = null)),
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-cookie-rules").toFile()

        try {
            repo.save(project.copy(endpoints = listOf(endpoint)), tempDir.absolutePath)

            val yaml = File(tempDir, "endpoints/cookie-request-rules-regression.yml").readText()
            assertTrue(yaml.contains("cookies:"))
            assertTrue(yaml.contains("match_type: equal_to"))

            val reloaded = repo.load(tempDir.absolutePath)
            val cookie = reloaded.endpoints.single().requestRules?.cookies?.single()
            assertEquals("session_id", cookie?.name)
            assertEquals("abc123", cookie?.match)
            assertEquals(MatchType.EQUAL_TO, cookie?.matchType)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save and reload preserves variant request match`() {
        val project = repo.load(sampleProjectPath)
        val endpoint = project.endpoints.first().copy(
            id = "variant-request-match-regression",
            variants = listOf(
                project.endpoints.first().variants.first().copy(
                    requestMatch = VariantRequestMatch(
                        query = mapOf("type" to "active"),
                        headers = mapOf("X-Role" to "admin"),
                        bodyContains = "currentUser",
                    ),
                    body = YamlValue.Str("ok"),
                    bodyFile = null,
                ),
            ),
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-variant-request-match").toFile()

        try {
            repo.save(project.copy(endpoints = listOf(endpoint)), tempDir.absolutePath)

            val yaml = File(tempDir, "endpoints/variant-request-match-regression.yml").readText()
            assertTrue(yaml.contains("request_match:"))
            assertTrue(yaml.contains("body_contains: \"currentUser\""))

            val reloaded = repo.load(tempDir.absolutePath)
            val requestMatch = reloaded.endpoints.single().variants.single().requestMatch
            assertEquals(mapOf("type" to "active"), requestMatch?.query)
            assertEquals(mapOf("X-Role" to "admin"), requestMatch?.headers)
            assertEquals("currentUser", requestMatch?.bodyContains)
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
            assertTrue(File(tempDir, "fixtures/responses").isDirectory)
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

    @Test
    fun `save externalizes inline response bodies into fixture files`() {
        val endpoint = EndpointDocument(
            id = "get-notes",
            method = "GET",
            path = "/api/v1/notes",
            variants = listOf(
                ProjectVariant(
                    name = "default",
                    isDefault = true,
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = YamlValue.Obj(
                        mapOf(
                            "notes" to YamlValue.Array(
                                listOf(
                                    YamlValue.Obj(mapOf("id" to YamlValue.Int(1), "title" to YamlValue.Str("Inbox")))
                                )
                            )
                        )
                    ),
                )
            ),
        )
        val project = MoqProject(
            manifest = ProjectManifest(
                name = "Fixtures",
                defaults = ProjectDefaults(
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = listOf(endpoint),
            projectPath = "/tmp/fixtures",
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-inline-to-fixture").toFile()

        try {
            repo.save(project, tempDir.absolutePath)

            val reloaded = repo.load(tempDir.absolutePath)
            val variant = reloaded.endpoints.single().variants.single()
            val bodyFile = requireNotNull(variant.bodyFile)
            val fixture = File(tempDir, bodyFile)

            assertNull(variant.body)
            assertEquals("fixtures/responses/get-notes/notes-default.json", bodyFile)
            assertTrue(fixture.isFile)
            assertTrue(fixture.readText().contains("\"Inbox\""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `save externalizes binary response bodies into binary fixtures`() {
        val endpoint = EndpointDocument(
            id = "get-image",
            method = "GET",
            path = "/assets/image.jpg",
            variants = listOf(
                ProjectVariant(
                    name = "default",
                    isDefault = true,
                    status = 200,
                    headers = mapOf("Content-Type" to "image/jpeg"),
                    body = YamlValue.Str("/9j/4AAQSkZJRg=="),
                )
            ),
        )
        val project = MoqProject(
            manifest = ProjectManifest(
                name = "Binary Fixtures",
                defaults = ProjectDefaults(
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = listOf(endpoint),
            projectPath = "/tmp/binary-fixtures",
        )
        val tempDir = kotlin.io.path.createTempDirectory("moqproj-binary-fixture").toFile()

        try {
            repo.save(project, tempDir.absolutePath)

            val reloaded = repo.load(tempDir.absolutePath)
            val variant = reloaded.endpoints.single().variants.single()
            val bodyFile = requireNotNull(variant.bodyFile)
            val fixture = File(tempDir, bodyFile)

            assertEquals("fixtures/responses/get-image/image-jpg-default.jpg", bodyFile)
            assertNull(variant.body)
            assertTrue(fixture.isFile)
            assertTrue(fixture.readBytes().contentEquals(byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun jsonElementToYamlValue(element: JsonElement): YamlValue = when (element) {
        is JsonNull -> YamlValue.Null
        is JsonPrimitive -> when {
            element.isString -> YamlValue.Str(element.content)
            element.booleanOrNull != null -> YamlValue.Bool(element.booleanOrNull!!)
            element.intOrNull != null -> YamlValue.Int(element.intOrNull!!)
            element.longOrNull != null -> YamlValue.Double(element.longOrNull!!.toDouble())
            element.doubleOrNull != null -> YamlValue.Double(element.doubleOrNull!!)
            else -> YamlValue.Str(element.content)
        }
        is JsonArray -> YamlValue.Array(element.map(::jsonElementToYamlValue))
        is JsonObject -> YamlValue.Obj(element.mapValues { (_, value) -> jsonElementToYamlValue(value) })
    }

	// -- path containment --

	private fun projectWithBodyFile(bodyFile: String, sourcePath: String) = MoqProject(
		manifest = ProjectManifest(
			name = "Traversal Test",
			defaults = ProjectDefaults(
				auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
				network = NetworkBehavior(),
			),
		),
		endpoints = listOf(
			EndpointDocument(
				id = "get-items",
				method = "GET",
				path = "/api/items",
				variants = listOf(
					ProjectVariant(
						name = "success",
						isDefault = true,
						status = 200,
						body = YamlValue.Str("ok"),
						bodyFile = null,
					),
					ProjectVariant(
						name = "escape",
						status = 200,
						body = YamlValue.Str("payload"),
						bodyFile = bodyFile,
					),
				),
			),
		),
		projectPath = sourcePath,
	)

	@Test
	fun `save rejects body_file outside the fixtures directory`() {
		val tempDir = kotlin.io.path.createTempDirectory("moqproj-traversal").toFile()
		val escaped = File(tempDir.parentFile, "moqproj-escaped-${'$'}{tempDir.name}.txt")
		try {
			val project = projectWithBodyFile("../${'$'}{escaped.name}", tempDir.absolutePath)
			assertFailsWith<IllegalStateException> {
				repo.save(project, tempDir.absolutePath)
			}
			assertFalse(escaped.exists())
		} finally {
			tempDir.deleteRecursively()
			escaped.delete()
		}
	}

	@Test
	fun `save rejects body_file that escapes via fixtures dot-dot`() {
		val tempDir = kotlin.io.path.createTempDirectory("moqproj-traversal-dotdot").toFile()
		val escaped = File(tempDir.parentFile, "moqproj-dotdot-${'$'}{tempDir.name}.txt")
		try {
			val project = projectWithBodyFile("fixtures/../../${'$'}{escaped.name}", tempDir.absolutePath)
			assertFailsWith<IllegalStateException> {
				repo.save(project, tempDir.absolutePath)
			}
			assertFalse(escaped.exists())
		} finally {
			tempDir.deleteRecursively()
			escaped.delete()
		}
	}

	@Test
	fun `save rejects endpoint id containing path separators`() {
		val tempDir = kotlin.io.path.createTempDirectory("moqproj-bad-id").toFile()
		try {
			val project = projectWithBodyFile("fixtures/ok.txt", tempDir.absolutePath)
			val malicious = project.copy(
				endpoints = project.endpoints.map { it.copy(id = "../escape") },
			)
			assertFailsWith<IllegalArgumentException> {
				repo.save(malicious, tempDir.absolutePath)
			}
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `readFixture refuses paths outside the fixtures directory`() {
		val tempDir = kotlin.io.path.createTempDirectory("moqproj-read-traversal").toFile()
		val secret = File(tempDir.parentFile, "moqproj-secret-${'$'}{tempDir.name}.txt")
		try {
			secret.writeText("sensitive")
			assertNull(repo.readFixture(tempDir.absolutePath, "../${'$'}{secret.name}"))
			assertNull(repo.readFixture(tempDir.absolutePath, "fixtures/../../${'$'}{secret.name}"))
		} finally {
			tempDir.deleteRecursively()
			secret.delete()
		}
	}

	@Test
	fun `readFixture still reads fixtures inside the project`() {
		val tempDir = kotlin.io.path.createTempDirectory("moqproj-read-ok").toFile()
		try {
			val fixturesDir = File(tempDir, "fixtures")
			fixturesDir.mkdirs()
			File(fixturesDir, "body.json").writeText("{\"ok\":true}")
			assertEquals("{\"ok\":true}", repo.readFixture(tempDir.absolutePath, "fixtures/body.json"))
		} finally {
			tempDir.deleteRecursively()
		}
	}
}
