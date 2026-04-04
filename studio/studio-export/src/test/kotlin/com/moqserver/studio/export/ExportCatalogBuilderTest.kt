package com.moqserver.studio.export

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportCatalogBuilderTest {

    private val sampleProject = MoqProject(
        manifest = ProjectManifest(
            name = "Test API",
            defaults = ProjectDefaults(
                auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                network = NetworkBehavior(),
            ),
        ),
        endpoints = listOf(
            EndpointDocument(
                id = "list-users",
                method = "GET",
                path = "/api/v1/users",
                referenceName = "listUsers",
                description = "Returns all users",
                variants = listOf(
                    ProjectVariant(
                        name = "success",
                        referenceName = "success",
                        description = "Successful response",
                        isDefault = true,
                        status = 200,
                        body = YamlValue.Str("ok"),
                    ),
                    ProjectVariant(
                        name = "empty",
                        referenceName = "empty",
                        status = 200,
                        body = YamlValue.Str("[]"),
                    ),
                    ProjectVariant(
                        name = "server error",
                        referenceName = "serverError",
                        description = "Internal server error",
                        status = 500,
                        body = YamlValue.Str("error"),
                    ),
                ),
            ),
            EndpointDocument(
                id = "create-user",
                method = "POST",
                path = "/api/v1/users",
                referenceName = "createUser",
                variants = listOf(
                    ProjectVariant(
                        name = "created",
                        referenceName = "created",
                        isDefault = true,
                        status = 201,
                        body = YamlValue.Str("ok"),
                    ),
                ),
            ),
        ),
        projectPath = "/tmp/test",
    )

    @Test
    fun `catalog sorts endpoints by reference name`() {
        val catalog = ExportCatalogBuilder.build(sampleProject)

        assertEquals(2, catalog.endpoints.size)
        assertEquals("createUser", catalog.endpoints[0].referenceName)
        assertEquals("listUsers", catalog.endpoints[1].referenceName)
    }

    @Test
    fun `catalog preserves project name`() {
        val catalog = ExportCatalogBuilder.build(sampleProject)

        assertEquals("Test API", catalog.projectName)
    }

    @Test
    fun `catalog maps endpoint fields correctly`() {
        val catalog = ExportCatalogBuilder.build(sampleProject)
        val listUsers = catalog.endpoints.find { it.referenceName == "listUsers" }!!

        assertEquals("GET", listUsers.method)
        assertEquals("/api/v1/users", listUsers.path)
        assertEquals("Returns all users", listUsers.description)
    }

    @Test
    fun `catalog maps variant fields correctly`() {
        val catalog = ExportCatalogBuilder.build(sampleProject)
        val listUsers = catalog.endpoints.find { it.referenceName == "listUsers" }!!

        assertEquals(3, listUsers.variants.size)
        val success = listUsers.variants[0]
        assertEquals("success", success.referenceName)
        assertEquals("success", success.name)
        assertEquals(200, success.status)
        assertTrue(success.isDefault)
        assertEquals("Successful response", success.description)

        val empty = listUsers.variants[1]
        assertFalse(empty.isDefault)
        assertNull(empty.description)
    }

    @Test
    fun `catalog endpoint without description has null`() {
        val catalog = ExportCatalogBuilder.build(sampleProject)
        val createUser = catalog.endpoints.find { it.referenceName == "createUser" }!!

        assertNull(createUser.description)
    }
}
