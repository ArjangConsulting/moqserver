package com.moqserver.studio.projectformat

import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

class ProjectRepository(
    private val codec: YamlProjectCodec = YamlProjectCodec(),
) {
    private val logger = loggerFor<ProjectRepository>()
    private val fixtureJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun load(projectPath: String): MoqProject {
        logger.info("Loading project from {}", projectPath)
        val dir = File(projectPath)
        require(dir.isDirectory) { "Not a directory: $projectPath" }

        val manifestFile = File(dir, MoqProjectFormat.MANIFEST_FILE)
        require(manifestFile.isFile) { "Missing ${MoqProjectFormat.MANIFEST_FILE} at: ${manifestFile.absolutePath}" }
        val manifest = codec.decodeManifest(manifestFile.readText())
        logger.debug("Loaded manifest: {}", manifest.name)

        val endpointsDir = File(dir, MoqProjectFormat.ENDPOINTS_DIR)
        require(endpointsDir.isDirectory) {
            "Missing ${MoqProjectFormat.ENDPOINTS_DIR}/ directory at: ${endpointsDir.absolutePath}"
        }

        val endpointFiles = endpointsDir.listFiles { f ->
            f.extension in MoqProjectFormat.YAML_EXTENSIONS
        }?.sortedBy { it.name } ?: emptyList()

        require(endpointFiles.isNotEmpty()) { "No endpoint files found in: ${endpointsDir.absolutePath}" }
        logger.debug("Found {} endpoint files", endpointFiles.size)

        val endpoints = endpointFiles.map { file ->
            try {
                codec.decodeEndpoint(file.readText())
            } catch (e: Exception) {
                logger.error("Invalid endpoint file {}: {}", file.name, e.message)
                throw IllegalStateException("Invalid endpoint file ${file.absolutePath}: ${e.message}", e)
            }
        }

        val project = MoqProject(
            manifest = manifest,
            endpoints = endpoints,
            projectPath = dir.canonicalPath,
        )
        logger.info("Project loaded: {} with {} endpoints", manifest.name, endpoints.size)
        return project
    }

    fun save(project: MoqProject, path: String) {
        logger.info("Saving project {} to {}", project.manifest.name, path)
        requireValid(project, "Project cannot be saved")

        val destination = File(path).absoluteFile
        val parent = destination.parentFile ?: error("Project path must have a parent directory: $path")
        parent.mkdirs()
        val transactionId = UUID.randomUUID().toString()
        val staging = File(parent, ".${destination.name}.tmp-$transactionId")
        val backup = File(parent, ".${destination.name}.backup-$transactionId")

        var replacementCompleted = false
        try {
            writeProject(project, staging)
            replaceProject(staging, destination, backup)
            replacementCompleted = true
        } finally {
            staging.deleteRecursively()
            if (replacementCompleted || destination.exists()) {
                backup.deleteRecursively()
            }
        }
    }

    private fun writeProject(project: MoqProject, dir: File) {
        val endpointsDir = File(dir, MoqProjectFormat.ENDPOINTS_DIR)
        val fixturesDir = File(dir, MoqProjectFormat.FIXTURES_DIR)

        dir.mkdirs()
        endpointsDir.mkdirs()
        fixturesDir.mkdirs()

        val manifestYaml = codec.encodeManifest(project.manifest)
        File(dir, MoqProjectFormat.MANIFEST_FILE).writeText(manifestYaml)

        val referencedFixtures = mutableSetOf<String>()
        val sourceRoot = File(project.projectPath).canonicalFile
        val persistedEndpoints = project.endpoints
            .sortedBy { it.id }
            .map { endpoint ->
                requireSafeEndpointId(endpoint.id)
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
        logger.debug(
            "Project saved with {} endpoints and {} fixtures",
            persistedEndpoints.size,
            referencedFixtures.size,
        )
    }

    private fun requireValid(project: MoqProject, context: String) {
        val validator = ProjectValidator { projectPath, bodyFile ->
            resolveContainedFixture(File(projectPath), bodyFile)?.isFile == true
        }
        val errors = validator.validate(project).filter { it.severity == ValidationDiagnostic.Severity.ERROR }
        require(errors.isEmpty()) {
            "$context:\n${errors.joinToString("\n") { "- ${it.message}" }}"
        }
    }

    private fun replaceProject(staging: File, destination: File, backup: File) {
        if (!destination.exists()) {
            moveDirectory(staging, destination)
            return
        }

        moveDirectory(destination, backup)
        try {
            moveDirectory(staging, destination)
        } catch (error: Exception) {
            if (destination.exists()) destination.deleteRecursively()
            try {
                moveDirectory(backup, destination)
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
            }
            throw error
        }
        backup.deleteRecursively()
    }

    private fun moveDirectory(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    fun readFixture(projectPath: String, bodyFile: String): String? {
        val file = resolveContainedFixture(File(projectPath), bodyFile)
        if (file == null) {
            logger.warn("Refusing to read fixture path outside the project bundle: {}", bodyFile)
            return null
        }
        return if (file.isFile) file.readText() else null
    }

    /**
     * Resolves [relativePath] against [root], returning null unless the result stays inside
     * the project's fixtures directory. Guards against `..`/absolute body_file values from
     * untrusted .moqproj bundles escaping the bundle on read or save.
     */
    private fun resolveContainedFixture(root: File, relativePath: String): File? {
        if (!relativePath.startsWith("${MoqProjectFormat.FIXTURES_DIR}/")) return null
        val file = File(root, relativePath)
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) file else null
    }

    private fun requireSafeEndpointId(id: String) {
        require(id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != "..") {
            "Endpoint id \"$id\" is not a safe file name."
        }
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

        val targetFile = resolveContainedFixture(destinationRoot, persistedBodyFile)
            ?: error(
                "Refusing to write fixture for endpoint \"${endpoint.id}\" variant \"${variant.name}\": " +
                    "body_file \"$persistedBodyFile\" escapes the project's ${MoqProjectFormat.FIXTURES_DIR}/ directory.",
            )
        targetFile.parentFile?.mkdirs()

        if (body != null) {
            targetFile.writeBytes(serializeVariantBody(variant, body))
        } else if (bodyFile != null) {
            val sourceFile = resolveContainedFixture(sourceRoot, bodyFile)
                ?.takeIf(File::isFile)
                ?: error(
                    "Missing fixture for endpoint \"${endpoint.id}\" variant \"${variant.name}\": $bodyFile",
                )
            sourceFile.copyTo(targetFile, overwrite = true)
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

    /**
     * Mirrors `InlineBody.resolve` in the Swift core (MoqCore/Project/BodyEncoding.swift).
     *
     * Binary intent is declared by `body_encoding`, never inferred. The previous implementation
     * guessed by pairing a binary-looking Content-Type with a base64-shaped string, which
     * misfired both ways — plain text that happened to be 4-aligned alphanumerics was decoded as
     * base64 — and disagreed with the Swift writer about what bytes a variant means.
     */
    private fun serializeVariantBody(variant: ProjectVariant, body: YamlValue): ByteArray {
        if (variant.bodyEncoding == BodyEncoding.BASE64) {
            val text = (body as? YamlValue.Str)?.value
                ?: throw IllegalStateException("body_encoding base64 requires body to be a string")
            // MIME decoder, not the strict one: YAML block scalars wrap long base64 payloads.
            return runCatching { Base64.getMimeDecoder().decode(text) }
                .getOrElse { throw IllegalStateException("body is not valid base64", it) }
        }

        return when (body) {
            is YamlValue.Str -> body.value.toByteArray(Charsets.UTF_8)
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

private fun YamlValue.toJsonElement(): JsonElement = when (this) {
    is YamlValue.Null -> JsonNull
    is YamlValue.Bool -> JsonPrimitive(value)
    is YamlValue.Int -> JsonPrimitive(value)
    is YamlValue.Double -> JsonPrimitive(value)
    is YamlValue.Str -> JsonPrimitive(value)
    is YamlValue.Array -> JsonArray(value.map { it.toJsonElement() })
    is YamlValue.Obj -> JsonObject(value.mapValues { (_, child) -> child.toJsonElement() })
}
