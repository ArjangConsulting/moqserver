package com.moqserver.studio.export.support

import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolSanitizerTest {

    @Test
    fun `toPascalCase converts camelCase`() {
        assertEquals("ListUsers", SymbolSanitizer.toPascalCase("listUsers"))
    }

    @Test
    fun `toPascalCase handles already pascal case`() {
        assertEquals("ListUsers", SymbolSanitizer.toPascalCase("ListUsers"))
    }

    @Test
    fun `toPascalCase handles underscores and hyphens`() {
        assertEquals("ListAllUsers", SymbolSanitizer.toPascalCase("list_all-users"))
    }

    @Test
    fun `toUpperSnakeCase converts camelCase`() {
        assertEquals("SERVER_ERROR", SymbolSanitizer.toUpperSnakeCase("serverError"))
    }

    @Test
    fun `toUpperSnakeCase preserves already upper snake`() {
        assertEquals("SERVER_ERROR", SymbolSanitizer.toUpperSnakeCase("SERVER_ERROR"))
    }

    @Test
    fun `toUpperSnakeCase handles single word`() {
        assertEquals("SUCCESS", SymbolSanitizer.toUpperSnakeCase("success"))
    }

    @Test
    fun `toCamelCase converts properly`() {
        assertEquals("listUsers", SymbolSanitizer.toCamelCase("listUsers"))
        assertEquals("serverError", SymbolSanitizer.toCamelCase("serverError"))
    }

    @Test
    fun `kotlinIdentifier escapes reserved words`() {
        assertEquals("`class`", SymbolSanitizer.kotlinIdentifier("class"))
        assertEquals("myClass", SymbolSanitizer.kotlinIdentifier("myClass"))
    }

    @Test
    fun `javaIdentifier prefixes reserved words`() {
        assertEquals("_class", SymbolSanitizer.javaIdentifier("class"))
        assertEquals("myClass", SymbolSanitizer.javaIdentifier("myClass"))
    }

    @Test
    fun `swiftIdentifier escapes reserved words`() {
        assertEquals("`self`", SymbolSanitizer.swiftIdentifier("self"))
        assertEquals("myVar", SymbolSanitizer.swiftIdentifier("myVar"))
    }

    @Test
    fun `cleanIdentifier handles leading digit`() {
        assertEquals("_123abc", SymbolSanitizer.kotlinIdentifier("123abc"))
    }
}
