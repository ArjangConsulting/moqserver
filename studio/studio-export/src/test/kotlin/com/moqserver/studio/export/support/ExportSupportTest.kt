package com.moqserver.studio.export.support

import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportSupportTest {

    @Test
    fun `allocator deduplicates endpoint names after normalization`() {
        val endpoints = listOf(
            ExportEndpoint("fooBar", "GET", "/one", null, emptyList()),
            ExportEndpoint("foo_bar", "GET", "/two", null, emptyList()),
        )

        val names = ExportNameAllocator.endpointNames(
            endpoints = endpoints,
            normalize = SymbolSanitizer::toPascalCase,
        )

        assertEquals("FooBar", names.getValue("fooBar"))
        assertEquals("FooBar2", names.getValue("foo_bar"))
    }

    @Test
    fun `allocator deduplicates variant names after normalization`() {
        val variants = listOf(
            ExportVariant("serverError", "Server Error", 500, false, null),
            ExportVariant("server_error", "Server Error 2", 502, false, null),
        )

        val names = ExportNameAllocator.variantNames(
            variants = variants,
            normalize = SymbolSanitizer::toUpperSnakeCase,
            withSuffix = { base, suffix -> "${base}_$suffix" },
        )

        assertEquals("SERVER_ERROR", names.getValue("serverError"))
        assertEquals("SERVER_ERROR_2", names.getValue("server_error"))
    }

    @Test
    fun `comment sanitizer neutralizes block comment terminators`() {
        assertEquals(
            listOf("first * / line", "second line"),
            ExportComments.lines("first */ line\nsecond line"),
        )
    }
}
