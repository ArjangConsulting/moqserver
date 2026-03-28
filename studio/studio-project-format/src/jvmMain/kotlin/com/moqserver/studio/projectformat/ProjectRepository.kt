package com.moqserver.studio.projectformat

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProjectRepository(
    private val codec: YamlProjectCodec = YamlProjectCodec(),
) {
    private val fixtureJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun load(projectPath: String): MoqProject {
        val dir = File(projectPath)
        require(dir.isDirectory) { "Not a directory: $projectPath" }

        val manifestFile = File(dir, MoqProjectFormat.MANIFEST_FILE)
        require(manifestFile.isFile) { "Missing ${MoqProjectFormat.MANIFEST_FILE} at: ${manifestFile.absolutePath}" }
        val manifest = codec.decodeManifest(manifestFile.readText())

        val endpointsDir = File(dir, MoqProjectFormat.ENDPOINTS_DIR)
        require(endpointsDir.isDirectory) {
            "Missing ${MoqProjectFormat.ENDPOINTS_DIR}/ directory at: ${endpointsDir.absolutePath}"
        }

        val endpointFiles = endpointsDir.listFiles { f ->
            f.extension in MoqProjectFormat.YAML_EXTENSIONS
        }?.sortedBy { it.name } ?: emptyList()

        require(endpointFiles.isNotEmpty()) { "No endpoint files found in: ${endpointsDir.absolutePath}" }

        val endpoints = endpointFiles.map { file ->
            try {
                codec.decodeEndpoint(file.readText())
            } catch (e: Exception) {
                throw IllegalStateException("Invalid endpoint file ${file.absolutePath}: ${e.message}", e)
            }
        }

        return MoqProject(
            manifest = manifest,
            endpoints = endpoints,
            projectPath = dir.canonicalPath,
        )
    }

    fun save(project: MoqProject, path: String) {
        val dir = File(path)
        val endpointsDir = File(dir, MoqProjectFormat.ENDPOINTS_DIR)
        val fixturesDir = File(dir, MoqProjectFormat.FIXTURES_DIR)

        dir.mkdirs()
        endpointsDir.mkdirs()
        fixturesDir.mkdirs()

        val manifestYaml = codec.encodeManifest(project.manifest)
        File(dir, MoqProjectFormat.MANIFEST_FILE).writeText(manifestYaml)

        val referencedFixtures = mutableSetOf<String>()
        val sourceRoot = File(project.projectPath)
        val persistedEndpoints = project.endpoints
            .sortedBy { it.id }
            .map { endpoint ->
                persistEndpoint(
                    endpoint = endpoint,
                    destinationRoot = dir,
                    sourceRoot = sourceRoot,
                    referencedFixtures = referencedFixtures,
                )
            }

        val expectedEndpointFiles = persistedEndpoints.map { "${it.id}.yml" }.toSet()
        endpointsDir.listFiles { file ->
            file.isFile && file.extension in MoqProjectFormat.YAML_EXTENSIONS
        }?.forEach { file ->
            if (file.name !in expectedEndpointFiles) {
                file.delete()
            }
        }
        for (endpoint in persistedEndpoints) {
            val endpointYaml = codec.encodeEndpoint(endpoint)
            File(endpointsDir, "${endpoint.id}.yml").writeText(endpointYaml)
        }

        fixturesDir.walkBottomUp()
            .filter { it != fixturesDir }
            .forEach { file ->
                val relativePath = file.relativeTo(dir).invariantSeparatorsPath
                when {
                    file.isFile && relativePath !in referencedFixtures -> file.delete()
                    file.isDirectory && file.listFiles().isNullOrEmpty() -> file.delete()
                }
            }
    }

    fun readFixture(projectPath: String, bodyFile: String): String? {
        val file = File(projectPath, bodyFile)
        return if (file.isFile) file.readText() else null
    }

    private fun persistEndpoint(
        endpoint: EndpointDocument,
        destinationRoot: File,
        sourceRoot: File,
        referencedFixtures: MutableSet<String>,
    ): EndpointDocument {
        val persistedVariants = endpoint.variants.map { variant ->
            persistVariant(
                endpoint = endpoint,
                variant = variant,
                destinationRoot = destinationRoot,
                sourceRoot = sourceRoot,
                referencedFixtures = referencedFixtures,
            )
        }
        return endpoint.copy(variants = persistedVariants)
    }

    private fun persistVariant(
        endpoint: EndpointDocument,
        variant: ProjectVariant,
        destinationRoot: File,
        sourceRoot: File,
        referencedFixtures: MutableSet<String>,
    ): ProjectVariant {
        val body = variant.body
        val bodyFile = variant.bodyFile

        if (body == null && bodyFile == null) {
            return variant
        }

        val persistedBodyFile = when {
            bodyFile != null && body == null -> bodyFile
            else -> bodyFile ?: buildFixturePath(endpoint, variant)
        }

        val targetFile = File(destinationRoot, persistedBodyFile)
        targetFile.parentFile?.mkdirs()

        if (body != null) {
            targetFile.writeBytes(serializeVariantBody(variant, body))
        } else if (bodyFile != null) {
            val sourceFile = File(sourceRoot, bodyFile)
            if (sourceFile.isFile && sourceFile.canonicalPath != targetFile.canonicalPath) {
                sourceFile.copyTo(targetFile, overwrite = true)
            }
        }

        referencedFixtures += persistedBodyFile
        return variant.copy(body = null, bodyFile = persistedBodyFile)
    }

    private fun buildFixturePath(endpoint: EndpointDocument, variant: ProjectVariant): String {
        val pathHint = endpoint.path
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?.let(::sanitizeFixtureSegment)
            ?: "response"
        val variantHint = sanitizeFixtureSegment(variant.name)
        val extension = fixtureExtension(endpoint, variant)
        return "${MoqProjectFormat.FIXTURE_RESPONSES_PREFIX}${endpoint.id}/$pathHint-$variantHint.$extension"
    }

    private fun fixtureExtension(endpoint: EndpointDocument, variant: ProjectVariant): String {
        variant.bodyFile?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.let { return it }

        val contentType = variant.headers
            ?.entries
            ?.firstOrNull { it.key.equals(MoqProjectFormat.CONTENT_TYPE_HEADER, ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()

        return when {
            contentType == null && variant.body is YamlValue.Obj -> "json"
            contentType == null && variant.body is YamlValue.Array -> "json"
            contentType == null && variant.body is YamlValue.Bool -> "json"
            contentType == null && variant.body is YamlValue.Int -> "json"
            contentType == null && variant.body is YamlValue.Double -> "json"
            contentType == null && endpoint.path.endsWith(".json", ignoreCase = true) -> "json"
            contentType == null && endpoint.path.endsWith(".xml", ignoreCase = true) -> "xml"
            contentType == null && endpoint.path.endsWith(".html", ignoreCase = true) -> "html"
            contentType == null -> "txt"
            "application/json" == contentType || contentType.endsWith("+json") -> "json"
            "application/xml" == contentType || "text/xml" == contentType || contentType.endsWith("+xml") -> "xml"
            "text/plain" == contentType -> "txt"
            "text/html" == contentType -> "html"
            "text/css" == contentType -> "css"
            "application/javascript" == contentType || "text/javascript" == contentType -> "js"
            "application/graphql-response+json" == contentType -> "json"
            "application/graphql" == contentType || "text/graphql" == contentType -> "graphql"
            "image/jpeg" == contentType -> "jpg"
            "image/png" == contentType -> "png"
            "image/gif" == contentType -> "gif"
            "image/webp" == contentType -> "webp"
            "image/svg+xml" == contentType -> "svg"
            "application/pdf" == contentType -> "pdf"
            else -> contentType.substringAfter('/').substringAfterLast('+').ifBlank { "bin" }
        }
    }

    private fun serializeVariantBody(variant: ProjectVariant, body: YamlValue): ByteArray {
        val contentType = variant.headers
            ?.entries
            ?.firstOrNull { it.key.equals(MoqProjectFormat.CONTENT_TYPE_HEADER, ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()

        return when (body) {
            is YamlValue.Str -> {
                if (contentType?.isLikelyBinaryContentType() == true && body.value.isLikelyBase64()) {
                    runCatching { Base64.getDecoder().decode(body.value) }
                        .getOrElse { body.value.toByteArray(Charsets.UTF_8) }
                } else {
                    body.value.toByteArray(Charsets.UTF_8)
                }
            }
            is YamlValue.Obj, is YamlValue.Array -> {
                fixtureJson.encodeToString(JsonElement.serializer(), body.toJsonElement())
                    .toByteArray(Charsets.UTF_8)
            }
            is YamlValue.Null,
            is YamlValue.Bool,
            is YamlValue.Int,
            is YamlValue.Double,
            -> body.toJsonElement().toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun sanitizeFixtureSegment(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "fixture" }
    }
}

private fun String.isLikelyBase64(): Boolean {
    val normalized = filterNot(Char::isWhitespace)
    if (normalized.isEmpty() || normalized.length % 4 != 0) return false
    return normalized.all { ch ->
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/' || ch == '='
    }
}

private fun String.isLikelyBinaryContentType(): Boolean {
    return startsWith("image/") ||
        startsWith("audio/") ||
        startsWith("video/") ||
        this == "application/pdf" ||
        this == "application/octet-stream" ||
        this == "application/zip" ||
        this == "application/gzip"
}

private fun YamlValue.toJsonElement(): JsonElement = when (this) {
    is YamlValue.Null -> JsonNull
    is YamlValue.Bool -> JsonPrimitive(value)
    is YamlValue.Int -> JsonPrimitive(value)
    is YamlValue.Double -> JsonPrimitive(value)
    is YamlValue.Str -> JsonPrimitive(value)
    is YamlValue.Array -> JsonArray(value.map { it.toJsonElement() })
    is YamlValue.Obj -> JsonObject(value.mapValues { (_, child) -> child.toJsonElement() })
}
