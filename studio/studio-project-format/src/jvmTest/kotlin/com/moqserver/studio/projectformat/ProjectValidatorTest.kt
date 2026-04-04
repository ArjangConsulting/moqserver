package com.moqserver.studio.projectformat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectValidatorTest {
    private val validator = ProjectValidator()

    @Test
    fun `rejects duplicate endpoint reference names`() {
        val diagnostics = validator.validate(
            projectWithEndpoints(
                EndpointDocument(
                    id = "get-pets",
                    method = "GET",
                    path = "/pets",
                    referenceName = "petsApi",
                    variants = listOf(ProjectVariant(name = "Success", referenceName = "success", status = 200)),
                ),
                EndpointDocument(
                    id = "post-pets",
                    method = "POST",
                    path = "/pets",
                    referenceName = "petsApi",
                    variants = listOf(ProjectVariant(name = "Created", referenceName = "created", status = 201)),
                ),
            )
        )

        assertTrue(diagnostics.any { it.field == "reference_name" && "Duplicate endpoint reference_name" in it.message })
    }

    @Test
    fun `rejects invalid and duplicate variant reference names`() {
        val diagnostics = validator.validate(
            projectWithEndpoints(
                EndpointDocument(
                    id = "get-pets",
                    method = "GET",
                    path = "/pets",
                    variants = listOf(
                        ProjectVariant(name = "Success", referenceName = "bad name", status = 200),
                        ProjectVariant(name = "Error", referenceName = "bad_name", status = 500),
                        ProjectVariant(name = "Error 2", referenceName = "bad_name", status = 502),
                    ),
                ),
            )
        )

        assertEquals(2, diagnostics.count { it.field?.endsWith("reference_name") == true })
        assertTrue(diagnostics.any { "must start with a letter or underscore" in it.message })
        assertTrue(diagnostics.any { "Duplicate variant reference_name" in it.message })
    }

    @Test
    fun `rejects empty variant request match`() {
        val diagnostics = validator.validate(
            projectWithEndpoints(
                EndpointDocument(
                    id = "get-pets",
                    method = "GET",
                    path = "/pets",
                    variants = listOf(
                        ProjectVariant(
                            name = "Success",
                            referenceName = "success",
                            status = 200,
                            requestMatch = VariantRequestMatch(),
                        )
                    ),
                ),
            )
        )

        assertTrue(diagnostics.any { it.field?.endsWith("request_match") == true && "must define query, headers, or body_contains" in it.message })
    }

    private fun projectWithEndpoints(vararg endpoints: EndpointDocument): MoqProject {
        return MoqProject(
            manifest = ProjectManifest(
                name = "Validation",
                defaults = ProjectDefaults(
                    auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                    network = NetworkBehavior(),
                ),
            ),
            endpoints = endpoints.toList(),
            projectPath = "/tmp/validation",
        )
    }
}
