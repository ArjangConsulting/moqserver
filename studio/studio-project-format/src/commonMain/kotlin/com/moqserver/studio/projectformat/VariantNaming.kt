package com.moqserver.studio.projectformat

private val generatedVariantNamePattern = Regex("(?i)^(default|success(?:-\\d+)?|error(?:-\\d+)?)$")

fun defaultVariantBaseName(status: Int): String {
    return when {
        status in 200..299 -> "Success"
        status in 400..599 -> "Error"
        else -> "Variant"
    }
}

/**
 * Sanitizes a string to a code-compatible identifier: only alphanumeric characters and
 * underscores, never starting with a digit. Spaces and other invalid characters are
 * replaced with underscores, consecutive underscores are collapsed, and leading/trailing
 * underscores are trimmed.
 */
fun sanitizeToCodeCompatibleName(name: String): String {
    val sanitized = name
        .trim()
        .replace(Regex("[^A-Za-z0-9_]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    if (sanitized.isEmpty()) return "_"
    return if (sanitized.first().isDigit()) "_$sanitized" else sanitized
}

fun suggestedVariantName(
    status: Int,
    existingNames: Collection<String> = emptyList(),
    preferredName: String? = null,
): String {
    val rawName = preferredName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { name ->
            if (generatedVariantNamePattern.matches(name)) {
                defaultVariantBaseName(status)
            } else {
                name
            }
        }
        ?: defaultVariantBaseName(status)

    val normalizedPreferredName = sanitizeToCodeCompatibleName(rawName)

    if (normalizedPreferredName !in existingNames) {
        return normalizedPreferredName
    }

    var suffix = 2
    while (true) {
        val candidate = "${normalizedPreferredName}_$suffix"
        if (candidate !in existingNames) {
            return candidate
        }
        suffix += 1
    }
}
