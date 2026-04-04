package com.moqserver.studio.projectformat

private val generatedVariantNamePattern = Regex("(?i)^(default|success(?:[-_]\\d+)?|error(?:[-_]\\d+)?)$")

fun defaultVariantBaseName(status: Int): String {
    return when {
        status in 200..299 -> "Success"
        status in 400..599 -> "Error"
        else -> "Variant"
    }
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

    val normalizedPreferredName = rawName.trim()

    if (normalizedPreferredName !in existingNames) {
        return normalizedPreferredName
    }

    var suffix = 2
    while (true) {
        val candidate = "$normalizedPreferredName $suffix"
        if (candidate !in existingNames) {
            return candidate
        }
        suffix += 1
    }
}
