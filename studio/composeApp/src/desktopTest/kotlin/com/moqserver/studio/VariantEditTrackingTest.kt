package com.moqserver.studio

import com.moqserver.studio.projectformat.ProjectVariant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariantEditTrackingTest {

    @Test
    fun `imported variant without current session changes is not treated as edited`() {
        val original = ProjectVariant(name = "Success", referenceName = "success", status = 200)

        assertFalse(original.hasSessionEdits(listOf(original)))
    }

    @Test
    fun `imported variant changed in current session is treated as edited`() {
        val original = ProjectVariant(name = "Success", referenceName = "success", status = 200)
        val updated = original.copy(status = 201)

        assertTrue(updated.hasSessionEdits(listOf(original)))
    }

    @Test
    fun `new pristine variant is not treated as edited`() {
        val variant = ProjectVariant(name = "Success", status = 200)

        assertFalse(variant.hasSessionEdits(emptyList()))
    }

    @Test
    fun `new customized variant is treated as edited`() {
        val variant = ProjectVariant(
            name = "Custom Success",
            referenceName = "customSuccess",
            status = 200,
        )

        assertTrue(variant.hasSessionEdits(emptyList()))
    }
}
