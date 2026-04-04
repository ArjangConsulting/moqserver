package com.moqserver.studio.export.support

/**
 * Sanitizes identifier strings for use in generated code.
 * Handles reserved keywords, invalid characters, and naming conventions.
 */
internal object SymbolSanitizer {

    private val KOTLIN_RESERVED = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private val JAVA_RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
    )

    private val JAVASCRIPT_RESERVED = setOf(
        "abstract", "arguments", "await", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "debugger", "default", "delete", "do", "double", "else",
        "enum", "eval", "export", "extends", "false", "final", "finally", "float", "for",
        "function", "goto", "if", "implements", "import", "in", "instanceof", "int",
        "interface", "let", "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "true", "try", "typeof", "undefined", "var", "void",
        "volatile", "while", "with", "yield",
    )

    private val SWIFT_RESERVED = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
        "import", "init", "inout", "internal", "let", "open", "operator", "private",
        "precedencegroup", "protocol", "public", "rethrows", "static", "struct", "subscript",
        "typealias", "var", "break", "case", "catch", "continue", "default", "defer", "do",
        "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch",
        "throw", "where", "while", "as", "Any", "catch", "false", "is", "nil", "rethrows",
        "self", "Self", "super", "throw", "throws", "true", "try",
    )

    /**
     * Sanitize a reference name as a Kotlin identifier.
     * Escapes reserved words with backticks, replaces invalid chars with underscores.
     */
    fun kotlinIdentifier(name: String): String {
        val cleaned = cleanIdentifier(name)
        return if (cleaned in KOTLIN_RESERVED) "`$cleaned`" else cleaned
    }

    /**
     * Sanitize a reference name as a Java identifier.
     * Prefixes reserved words with underscore, replaces invalid chars with underscores.
     */
    fun javaIdentifier(name: String): String {
        val cleaned = cleanIdentifier(name)
        return if (cleaned in JAVA_RESERVED) "_$cleaned" else cleaned
    }

    /**
     * Sanitize a reference name as a JavaScript identifier.
     * Prefixes reserved words with underscore, replaces invalid chars with underscores.
     */
    fun javaScriptIdentifier(name: String): String {
        val cleaned = cleanIdentifier(name)
        return if (cleaned in JAVASCRIPT_RESERVED) "_$cleaned" else cleaned
    }

    /**
     * Sanitize a reference name as a Swift identifier.
     * Escapes reserved words with backticks, replaces invalid chars with underscores.
     */
    fun swiftIdentifier(name: String): String {
        val cleaned = cleanIdentifier(name)
        return if (cleaned in SWIFT_RESERVED) "`$cleaned`" else cleaned
    }

    /**
     * Convert a reference name to PascalCase suitable for type names.
     */
    fun toPascalCase(name: String): String {
        return name.split(Regex("[^a-zA-Z0-9]"))
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifEmpty { "Unknown" }
    }

    /**
     * Convert a reference name to UPPER_SNAKE_CASE suitable for enum constants.
     */
    fun toUpperSnakeCase(name: String): String {
        val result = StringBuilder()
        for ((i, ch) in name.withIndex()) {
            if (ch.isUpperCase() && i > 0 && name[i - 1].isLowerCase()) {
                result.append('_')
            }
            if (ch.isLetterOrDigit()) {
                result.append(ch.uppercase())
            } else if (ch == '_' || ch == '-') {
                result.append('_')
            }
        }
        return result.toString()
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "UNKNOWN" }
    }

    /**
     * Convert a reference name to camelCase.
     */
    fun toCamelCase(name: String): String {
        val pascal = toPascalCase(name)
        return pascal.replaceFirstChar { it.lowercase() }
    }

    private fun cleanIdentifier(name: String): String {
        val result = name.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_') ch else '_'
        }.joinToString("")
        return if (result.firstOrNull()?.isDigit() == true) "_$result" else result.ifEmpty { "_" }
    }
}
