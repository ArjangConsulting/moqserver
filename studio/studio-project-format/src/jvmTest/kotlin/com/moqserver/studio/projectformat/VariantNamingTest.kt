package com.moqserver.studio.projectformat

import kotlin.test.Test
import kotlin.test.assertEquals

class VariantNamingTest {
    @Test
    fun `defaults success and error variant names from status code`() {
        assertEquals("Success", suggestedVariantName(status = 200))
        assertEquals("Error", suggestedVariantName(status = 500))
        assertEquals("Variant", suggestedVariantName(status = 302))
    }

    @Test
    fun `normalizes imported generated names and keeps them unique`() {
        val names = mutableListOf<String>()

        val success = suggestedVariantName(status = 200, existingNames = names, preferredName = "default")
        names += success
        val successTwo = suggestedVariantName(status = 201, existingNames = names, preferredName = "success-201")
        names += successTwo
        val error = suggestedVariantName(status = 404, existingNames = names, preferredName = "error-404")

        assertEquals("Success", success)
        assertEquals("Success_2", successTwo)
        assertEquals("Error", error)
    }

    @Test
    fun `preserves custom names that are already code-compatible`() {
        assertEquals(
            "Unauthorized",
            suggestedVariantName(status = 401, preferredName = "Unauthorized"),
        )
    }

    @Test
    fun `sanitizes names with spaces to use underscores`() {
        assertEquals("Not_Found", suggestedVariantName(status = 404, preferredName = "Not Found"))
        assertEquals("Internal_Server_Error", suggestedVariantName(status = 500, preferredName = "Internal Server Error"))
    }

    @Test
    fun `sanitizes names that start with a digit`() {
        assertEquals("_404", sanitizeToCodeCompatibleName("404"))
        assertEquals("_2xx_success", sanitizeToCodeCompatibleName("2xx success"))
    }

    @Test
    fun `collapses consecutive special characters into a single underscore`() {
        assertEquals("Not_Found", sanitizeToCodeCompatibleName("Not--Found"))
        assertEquals("Server_Error", sanitizeToCodeCompatibleName("Server  Error"))
    }

    @Test
    fun `builds code friendly reference names`() {
        assertEquals("getUserProfile", suggestedEndpointReferenceName("Get User Profile", fallbackId = "get-user-profile"))
        assertEquals("serverError", suggestedVariantReferenceName("Server Error", status = 500))
        assertEquals("serverError2", suggestedVariantReferenceName("Server Error", status = 500, existingNames = listOf("serverError")))
    }
}
