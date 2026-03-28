package com.moqserver.studio.projectformat

private val referenceNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun isValidReferenceName(value: String): Boolean {
    return referenceNamePattern.matches(value)
}

fun defaultReferenceNameForEndpointId(id: String): String {
    return toReferenceName(id, fallbackPrefix = "endpoint")
}

fun defaultReferenceNameForVariantName(name: String): String {
    return toReferenceName(name, fallbackPrefix = "variant")
}

fun suggestedEndpointReferenceName(
    preferredSource: String?,
    fallbackId: String,
    existingNames: Collection<String> = emptyList(),
): String {
    val baseName = preferredSource
        ?.takeIf { it.isNotBlank() }
        ?.let { toReferenceName(it, fallbackPrefix = "endpoint") }
        ?: defaultReferenceNameForEndpointId(fallbackId)
    return uniqueReferenceName(baseName, existingNames)
}

fun suggestedVariantReferenceName(
    preferredSource: String?,
    status: Int,
    existingNames: Collection<String> = emptyList(),
): String {
    val fallbackSource = preferredSource
        ?.takeIf { it.isNotBlank() }
        ?: defaultVariantBaseName(status)
    val baseName = toReferenceName(fallbackSource, fallbackPrefix = "variant")
    return uniqueReferenceName(baseName, existingNames)
}

private fun uniqueReferenceName(baseName: String, existingNames: Collection<String>): String {
    if (baseName !in existingNames) {
        return baseName
    }

    var suffix = 2
    while (true) {
        val candidate = "$baseName$suffix"
        if (candidate !in existingNames) {
            return candidate
        }
        suffix += 1
    }
}

private fun toReferenceName(source: String, fallbackPrefix: String): String {
    val tokens = source
        .trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("[^A-Za-z0-9_]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    val normalized = buildString {
        tokens.forEachIndexed { index, token ->
            val cleanToken = token.trim('_').takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val lowercaseToken = cleanToken.lowercase()
            if (index == 0) {
                append(lowercaseToken)
            } else {
                append(lowercaseToken.replaceFirstChar(Char::titlecase))
            }
        }
    }

    if (normalized.isBlank()) {
        return fallbackPrefix
    }

    return if (normalized.first().isLetter() || normalized.first() == '_') {
        normalized
    } else {
        fallbackPrefix + normalized.replaceFirstChar(Char::titlecase)
    }
}
