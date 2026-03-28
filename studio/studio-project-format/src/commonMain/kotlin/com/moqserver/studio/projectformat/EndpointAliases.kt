package com.moqserver.studio.projectformat

val EndpointDocument.displayAlias: String
    get() = alias?.takeIf { it.isNotBlank() } ?: defaultAliasForEndpoint(
        method = method,
        path = path,
        operation = operation,
    )

fun defaultAliasForEndpoint(
    method: String,
    path: String,
    operation: EndpointOperation? = null,
): String {
    operation?.name
        ?.takeIf { it.isNotBlank() }
        ?.let(::humanizeAliasSource)
        ?.let { return it }

    if (path.equals(MoqProjectFormat.GRAPHQL_PATH, ignoreCase = true)) {
        val operationLabel = operation?.type
            ?.name
            ?.lowercase()
            ?.replaceFirstChar(Char::titlecase)
            ?: "GraphQL"
        return "$operationLabel Operation"
    }

    val verb = defaultAliasVerb(method = method, path = path)
    val segments = path
        .substringBefore('?')
        .split('/')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot(::isIgnoredPathSegment)

    val resourceSegments = segments.filterNot(::isPathParameter)
    val parameterSegments = segments.filter(::isPathParameter)

    val resourceLabel = resourceSegments
        .flatMap(::tokenizeAliasSource)
        .ifEmpty { listOf("Root") }
        .joinToString(" ")

    val parameterLabel = parameterSegments
        .flatMap { segment -> tokenizeAliasSource(segment.removeSurrounding("{", "}")) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ", prefix = " By ")
        .orEmpty()

    return "$verb $resourceLabel$parameterLabel"
}

fun humanizeAliasSource(source: String): String {
    return tokenizeAliasSource(source).joinToString(" ")
}

private fun defaultAliasVerb(method: String, path: String): String {
    return when (method.uppercase()) {
        "GET" -> if (path.contains('{')) "Get" else "List"
        "POST" -> "Create"
        "PUT", "PATCH" -> "Update"
        "DELETE" -> "Delete"
        "HEAD" -> "Head"
        "OPTIONS" -> "Options"
        else -> method.uppercase()
    }
}

private fun tokenizeAliasSource(source: String): List<String> {
    return source
        .trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("[^A-Za-z0-9]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .map { token ->
            if (token.length > 1 && token.all(Char::isUpperCase)) {
                token
            } else {
                token.lowercase().replaceFirstChar(Char::titlecase)
            }
        }
}

private fun isIgnoredPathSegment(segment: String): Boolean {
    val normalized = segment.lowercase()
    return normalized == "api" || Regex("^v\\d+$", RegexOption.IGNORE_CASE).matches(segment)
}

private fun isPathParameter(segment: String): Boolean {
    return segment.startsWith("{") && segment.endsWith("}")
}
