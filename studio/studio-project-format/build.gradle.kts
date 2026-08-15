import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The .moqproj model types are generated from format/schema.json rather than hand-written, so the
// schema is the single source of truth for the wire/file contract in both languages. Output goes
// to build/ and is never committed: regenerating on every build makes drift impossible, which is
// a stronger guarantee than a committed copy plus a CI check.
// A typed task rather than a `doLast` block: the configuration cache cannot serialize references
// to build-script objects captured by an ad-hoc action, so the generator is constructed inside the
// task action instead of closed over.
@CacheableTask
abstract class GenerateProjectModels : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schema: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("com/moqserver/studio/projectformat")
        packageDir.mkdirs()
        packageDir.resolve("ProjectModels.generated.kt")
            .writeText(ProjectModelsGenerator(schema.get().asFile).generate())
    }
}

val generateProjectModels by tasks.registering(GenerateProjectModels::class) {
    description = "Generates .moqproj model classes from format/schema.json"
    group = "build"
    schema.set(rootProject.layout.projectDirectory.file("../format/schema.json"))
    outputDir.set(layout.buildDirectory.dir("generated/moqformat/commonMain/kotlin"))
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateProjectModels)
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":studio-logging"))
                implementation(libs.snakeyaml.engine)
                implementation(libs.coroutines.core)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

/**
 * Emits Kotlin data classes and enums for the `$defs` in format/schema.json.
 *
 * The schema is regular enough that the mapping is mechanical; the exceptions live in the three
 * override tables below rather than in hand-edited output. Property order follows the schema so
 * that positional construction at call sites stays meaningful — and where a schema change would
 * reorder parameters, the Kotlin compiler catches the affected call sites.
 */
class ProjectModelsGenerator(schemaFile: File) {

    @Suppress("UNCHECKED_CAST")
    private val defs: Map<String, Map<String, Any?>> =
        (JsonSlurper().parse(schemaFile) as Map<String, Any?>)["\$defs"] as Map<String, Map<String, Any?>>

    /** Schema `$defs` name -> generated Kotlin type. Definitions absent here are not emitted. */
    private val classNames = mapOf(
        "projectManifest" to "ProjectManifest",
        "authConfig" to "ProjectAuthConfig",
        "networkConfig" to "NetworkBehavior",
        "requestRules" to "RequestRules",
        "ruleMatcher" to "RuleMatcher",
        "graphqlOperation" to "EndpointOperation",
        "requestMatch" to "VariantRequestMatch",
        "variant" to "ProjectVariant",
        "endpoint" to "EndpointDocument",
    )

    /** `$defs` that resolve to an existing Kotlin type instead of a generated one. */
    private val refTypes = mapOf(
        "httpMethod" to "String",
        "authType" to "AuthType",
        "graphqlOperationType" to "OperationType",
        "headersMap" to "Map<String, String>",
    )

    /**
     * Per-property overrides, keyed by "<def>.<schema property>". These are the places where the
     * Kotlin model deliberately says more than JSON Schema can: computed defaults, a Kotlin
     * property name that differs from the wire name, and the untyped body.
     */
    private val overrides = mapOf(
        "projectManifest.version" to Override(type = "String", default = "\"1\""),
        // Inline objects in the manifest that Kotlin models as named types; without these they
        // would fall through to the generic "object" mapping.
        "projectManifest.defaults" to Override(type = "ProjectDefaults"),
        "projectManifest.global_rules" to Override(type = "GlobalRules?"),
        "projectManifest.defaults.delay_ms" to Override(type = "Int", default = "0"),
        "endpoint.reference_name" to
            Override(type = "String", default = "defaultReferenceNameForEndpointId(id)"),
        "variant.reference_name" to
            Override(type = "String", default = "defaultReferenceNameForVariantName(name)"),
        "variant.default" to Override(type = "Boolean?", propertyName = "isDefault"),
        "variant.body" to Override(type = "YamlValue?"),
        "variant.body_encoding" to Override(type = "BodyEncoding?"),
        "ruleMatcher.match_type" to Override(type = "MatchType?"),
    )

    private data class Override(
        val type: String,
        val default: String? = null,
        val propertyName: String? = null,
    )

    private val out = StringBuilder()

    fun generate(): String {
        out.append(
            """
            |// GENERATED FROM format/schema.json — DO NOT EDIT.
            |// Regenerate with: ./gradlew :studio-project-format:generateProjectModels
            |//
            |// Types that cannot be derived from the schema (the MoqProject aggregate, filesystem
            |// layout constants, YamlValue) stay hand-written in ProjectModels.kt.
            |
            |package com.moqserver.studio.projectformat
            |
            |import kotlinx.serialization.SerialName
            |import kotlinx.serialization.Serializable
            |
            |""".trimMargin()
        )

        emitEnum("AuthType", enumValues("authType"))
        emitEnum("OperationType", enumValues("graphqlOperationType"))
        emitEnum("MatchType", enumValues("ruleMatcher", "match_type"))
        emitEnum("BodyEncoding", enumValues("variant", "body_encoding"))

        classNames.forEach { (defName, className) -> emitClass(defName, className, defs.getValue(defName)) }

        // Nested inline objects inside projectManifest that Kotlin models as named types.
        val manifestProps = properties(defs.getValue("projectManifest"))
        emitClass("projectManifest.defaults", "ProjectDefaults", objectAt(manifestProps, "defaults"))
        emitClass("projectManifest.global_rules", "GlobalRules", objectAt(manifestProps, "global_rules"))

        emitFormatRules()
        return out.toString()
    }

    // --- schema access -------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun properties(def: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (def["properties"] as? Map<String, Map<String, Any?>>).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun objectAt(props: Map<String, Map<String, Any?>>, key: String): Map<String, Any?> =
        props.getValue(key)

    @Suppress("UNCHECKED_CAST")
    private fun enumValues(defName: String): List<String> =
        defs.getValue(defName)["enum"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun enumValues(defName: String, property: String): List<String> =
        properties(defs.getValue(defName)).getValue(property)["enum"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun required(def: Map<String, Any?>): Set<String> =
        (def["required"] as? List<String>).orEmpty().toSet()

    // --- emission ------------------------------------------------------------------------

    private fun emitEnum(name: String, values: List<String>) {
        out.append("\n@Serializable\nenum class $name {\n")
        values.forEach { value ->
            out.append("    @SerialName(\"$value\") ${constantName(value)},\n")
        }
        out.append("}\n")
    }

    private fun emitClass(defKey: String, className: String, def: Map<String, Any?>) {
        val required = required(def)
        out.append("\n@Serializable\ndata class $className(\n")
        properties(def).forEach { (schemaName, schema) ->
            val override = overrides["$defKey.$schemaName"]
            val propertyName = override?.propertyName ?: camelCase(schemaName)
            val isRequired = schemaName in required
            val type = override?.type ?: kotlinType(schema, nullable = !isRequired)
            val default = override?.default ?: if (isRequired) null else "null"

            if (propertyName != schemaName) {
                out.append("    @SerialName(\"$schemaName\")\n")
            }
            out.append("    val $propertyName: $type")
            default?.let { out.append(" = $it") }
            out.append(",\n")
        }
        out.append(")\n")
    }

    /** Mirrors Swift's MoqFormatRules so both languages read the same constants from the schema. */
    private fun emitFormatRules() {
        val methods = enumValues("httpMethod").joinToString(", ") { "\"$it\"" }
        val idPattern = properties(defs.getValue("endpoint")).getValue("id")["pattern"] as String
        val version = properties(defs.getValue("projectManifest")).getValue("version")["const"] as String
        out.append(
            """
            |
            |/** Format constants derived from the schema. */
            |object MoqFormatRules {
            |    const val FORMAT_VERSION: String = "$version"
            |    const val ENDPOINT_ID_PATTERN: String = "$idPattern"
            |    val SUPPORTED_METHODS: Set<String> = setOf($methods)
            |
            |    fun isValidEndpointId(id: String): Boolean = Regex(ENDPOINT_ID_PATTERN).matches(id)
            |
            |    fun isSupportedMethod(method: String): Boolean = method.uppercase() in SUPPORTED_METHODS
            |}
            |""".trimMargin()
        )
    }

    // --- type mapping --------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun kotlinType(schema: Map<String, Any?>, nullable: Boolean): String {
        val suffix = if (nullable) "?" else ""
        (schema["\$ref"] as? String)?.let { ref ->
            val defName = ref.removePrefix("#/\$defs/")
            val type = refTypes[defName] ?: classNames[defName]
                ?: error("Schema \$ref to '$defName' has no Kotlin mapping")
            return "$type$suffix"
        }
        return when (val type = schema["type"] as? String) {
            "string" -> "String$suffix"
            "integer" -> "Int$suffix"
            "number" -> "Double$suffix"
            "boolean" -> "Boolean$suffix"
            "array" -> {
                val items = schema["items"] as Map<String, Any?>
                "List<${kotlinType(items, nullable = false)}>$suffix"
            }
            "object" -> "Map<String, String>$suffix"
            // A union such as ["string", "null"] is always optional on the Kotlin side.
            null -> if (schema["type"] is List<*>) "String?" else "YamlValue$suffix"
            else -> error("Unsupported schema type '$type'")
        }
    }

    private fun camelCase(name: String): String =
        name.split("_").mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar(Char::uppercase)
        }.joinToString("")

    private fun constantName(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
