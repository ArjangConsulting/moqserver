package com.moqserver.studio

import com.moqserver.studio.endpointdetail.defaultNewVariantStatus
import com.moqserver.studio.endpointdetail.editableBodyText
import com.moqserver.studio.endpointdetail.normalize
import com.moqserver.studio.endpointdetail.parseEditableText
import com.moqserver.studio.endpointdetail.removeVariantAt
import com.moqserver.studio.endpointdetail.updated
import com.moqserver.studio.endpointdetail.yamlValueToDisplayString
import com.moqserver.studio.endpointdetail.yamlValueToJsonString
import com.moqserver.studio.endpointdetail.yamlValueToPlainText
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlValueConversionTest {

    // -- yamlValueToDisplayString -----------------------------------------

    @Test
    fun `yamlValueToDisplayString formats primitives`() {
        assertEquals("null", yamlValueToDisplayString(YamlValue.Null))
        assertEquals("true", yamlValueToDisplayString(YamlValue.Bool(true)))
        assertEquals("false", yamlValueToDisplayString(YamlValue.Bool(false)))
        assertEquals("42", yamlValueToDisplayString(YamlValue.Int(42)))
        assertEquals("3.14", yamlValueToDisplayString(YamlValue.Double(3.14)))
        assertEquals("\"hello\"", yamlValueToDisplayString(YamlValue.Str("hello")))
    }

    @Test
    fun `yamlValueToDisplayString formats empty collections`() {
        assertEquals("[]", yamlValueToDisplayString(YamlValue.Array(emptyList())))
        assertEquals("{}", yamlValueToDisplayString(YamlValue.Obj(emptyMap())))
    }

    @Test
    fun `yamlValueToDisplayString formats flat object`() {
        val obj = YamlValue.Obj(mapOf("name" to YamlValue.Str("test"), "count" to YamlValue.Int(3)))
        val result = yamlValueToDisplayString(obj)
        assertEquals("name: \"test\"\ncount: 3", result)
    }

    // -- yamlValueToJsonString --------------------------------------------

    @Test
    fun `yamlValueToJsonString produces valid JSON for object`() {
        val obj = YamlValue.Obj(mapOf("ok" to YamlValue.Bool(true)))
        val result = yamlValueToJsonString(obj)
        assert(result.contains("\"ok\": true")) { "Expected JSON key/value but got: $result" }
    }

    @Test
    fun `yamlValueToJsonString escapes special characters in strings`() {
        val str = YamlValue.Str("line\"break")
        assertEquals("\"line\\\"break\"", yamlValueToJsonString(str))
    }

    // -- yamlValueToPlainText ---------------------------------------------

    @Test
    fun `yamlValueToPlainText returns empty string for null`() {
        assertEquals("", yamlValueToPlainText(YamlValue.Null))
    }

    @Test
    fun `yamlValueToPlainText returns raw string content`() {
        assertEquals("hello world", yamlValueToPlainText(YamlValue.Str("hello world")))
    }

    // -- parseEditableText ------------------------------------------------

    @Test
    fun `parseEditableText preserves type based on original`() {
        assertEquals(YamlValue.Int(99), parseEditableText("99", YamlValue.Int(0)))
        assertEquals(YamlValue.Bool(true), parseEditableText("true", YamlValue.Bool(false)))
        assertEquals(YamlValue.Double(1.5), parseEditableText("1.5", YamlValue.Double(0.0)))
        assertEquals(YamlValue.Null, parseEditableText("null", YamlValue.Null))
    }

    @Test
    fun `parseEditableText falls back to string when type does not match`() {
        assertEquals(YamlValue.Str("not-a-number"), parseEditableText("not-a-number", YamlValue.Int(0)))
        assertEquals(YamlValue.Str("maybe"), parseEditableText("maybe", YamlValue.Bool(false)))
    }

    @Test
    fun `parseEditableText with null original returns string`() {
        assertEquals(YamlValue.Str("anything"), parseEditableText("anything", null))
    }

    // -- editableBodyText -------------------------------------------------

    @Test
    fun `editableBodyText returns JSON for JSON-content-type variant`() {
        val variant = ProjectVariant(
            name = "JSON",
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
        )
        val body = YamlValue.Obj(mapOf("key" to YamlValue.Str("value")))
        val result = editableBodyText(variant, body)
        assert(result.contains("\"key\"")) { "Expected JSON output but got: $result" }
    }

    @Test
    fun `editableBodyText returns plain string for non-JSON variant`() {
        val variant = ProjectVariant(
            name = "Plain",
            status = 200,
            headers = mapOf("Content-Type" to "text/plain"),
        )
        val body = YamlValue.Str("hello")
        assertEquals("hello", editableBodyText(variant, body))
    }
}

class VariantAndRuleHelpersTest {

    // -- defaultNewVariantStatus ------------------------------------------

    @Test
    fun `defaultNewVariantStatus returns 200 when no 2xx variant exists`() {
        val variants = listOf(
            ProjectVariant(name = "Error", status = 500),
        )
        assertEquals(200, defaultNewVariantStatus(variants))
    }

    @Test
    fun `defaultNewVariantStatus returns 500 when no 4xx-5xx variant exists`() {
        val variants = listOf(
            ProjectVariant(name = "Success", status = 200),
        )
        assertEquals(500, defaultNewVariantStatus(variants))
    }

    @Test
    fun `defaultNewVariantStatus alternates when both ranges exist`() {
        val variants = listOf(
            ProjectVariant(name = "Success", status = 200),
            ProjectVariant(name = "Error", status = 500),
        )
        assertEquals(200, defaultNewVariantStatus(variants))
    }

    // -- updated (list helper) --------------------------------------------

    @Test
    fun `updated replaces element at index`() {
        val original = listOf("a", "b", "c")
        assertEquals(listOf("a", "X", "c"), original.updated(1, "X"))
    }

    // -- normalize (RequestRules) -----------------------------------------

    @Test
    fun `normalize returns null when all rule lists are empty`() {
        val rules = RequestRules(headers = emptyList(), queryParams = emptyList(), cookies = emptyList())
        assertNull(rules.normalize())
    }

    @Test
    fun `normalize preserves rules when headers are present`() {
        val rules = RequestRules(headers = listOf(RuleMatcher(name = "Auth", required = true)))
        assertNotNull(rules.normalize())
    }
}

class FileDialogHelpersTest {

    // -- buildProjectPackageName ------------------------------------------

    @Test
    fun `buildProjectPackageName sanitizes special characters`() {
        assertEquals("My Project.moqproj", buildProjectPackageName("My/Project"))
        assertEquals("My Project.moqproj", buildProjectPackageName("My:Project"))
    }

    @Test
    fun `buildProjectPackageName does not double-append extension`() {
        assertEquals("Test.moqproj", buildProjectPackageName("Test.moqproj"))
    }

    @Test
    fun `buildProjectPackageName handles blank input`() {
        assertEquals("Untitled Project.moqproj", buildProjectPackageName(""))
        assertEquals("Untitled Project.moqproj", buildProjectPackageName("   "))
    }

    // -- normalizeProjectPackagePath --------------------------------------

    @Test
    fun `normalizeProjectPackagePath preserves existing extension`() {
        val file = java.io.File("/tmp/My Project.moqproj")
        assertEquals(file, normalizeProjectPackagePath(file))
    }

    @Test
    fun `normalizeProjectPackagePath appends extension when missing`() {
        val file = java.io.File("/tmp/My Project")
        assertEquals(java.io.File("/tmp/My Project.moqproj"), normalizeProjectPackagePath(file))
    }

    // -- projectPickerInitialDirectory ------------------------------------

    @Test
    fun `projectPickerInitialDirectory returns null for null input`() {
        assertNull(projectPickerInitialDirectory(null))
    }

    @Test
    fun `projectPickerInitialDirectory returns null for non-existent directory`() {
        assertNull(projectPickerInitialDirectory("/nonexistent/path/that/does/not/exist"))
    }

    @Test
    fun `projectPickerInitialDirectory returns parent for moqproj directory`() {
        val parent = kotlin.io.path.createTempDirectory("moqproj-picker-test").toFile()
        val moqDir = java.io.File(parent, "test.moqproj").apply { mkdirs() }
        try {
            assertEquals(parent.canonicalPath, projectPickerInitialDirectory(moqDir.absolutePath))
        } finally {
            parent.deleteRecursively()
        }
    }
}

class RemoveVariantAtTest {

    private fun endpoint(vararg variants: ProjectVariant) = EndpointDocument(
        id = "test",
        method = "GET",
        path = "/test",
        variants = variants.toList(),
    )

    @Test
    fun `removing a non-default variant leaves remaining variants unchanged`() {
        val defaultVariant = ProjectVariant(name = "Success", status = 200, isDefault = true)
        val errorVariant = ProjectVariant(name = "Error", status = 500)
        val result = endpoint(defaultVariant, errorVariant).removeVariantAt(1)

        assertEquals(1, result.variants.size)
        assertTrue(result.variants.single().isDefault == true)
    }

    @Test
    fun `removing the default variant promotes the next variant to default`() {
        val defaultVariant = ProjectVariant(name = "Success", status = 200, isDefault = true)
        val errorVariant = ProjectVariant(name = "Error", status = 500)
        val result = endpoint(defaultVariant, errorVariant).removeVariantAt(0)

        assertEquals(1, result.variants.size)
        assertEquals("Error", result.variants.single().name)
        assertTrue(result.variants.single().isDefault == true)
    }

    @Test
    fun `removing the last default variant promotes the preceding variant to default`() {
        val okVariant = ProjectVariant(name = "OK", status = 200)
        val defaultVariant = ProjectVariant(name = "Success", status = 201, isDefault = true)
        val result = endpoint(okVariant, defaultVariant).removeVariantAt(1)

        assertEquals(1, result.variants.size)
        assertEquals("OK", result.variants.single().name)
        assertTrue(result.variants.single().isDefault == true)
    }

    @Test
    fun `removing the default variant from three variants promotes the one at the same index`() {
        val defaultVariant = ProjectVariant(name = "Default", status = 200, isDefault = true)
        val second = ProjectVariant(name = "Second", status = 201)
        val third = ProjectVariant(name = "Third", status = 500)
        val result = endpoint(defaultVariant, second, third).removeVariantAt(0)

        assertEquals(2, result.variants.size)
        assertTrue(result.variants[0].isDefault == true)
        assertFalse(result.variants[1].isDefault == true)
    }

    @Test
    fun `removing a variant from an out-of-bounds index returns endpoint unchanged`() {
        val variant = ProjectVariant(name = "Success", status = 200)
        val original = endpoint(variant)
        val result = original.removeVariantAt(5)

        assertEquals(original, result)
    }
}
