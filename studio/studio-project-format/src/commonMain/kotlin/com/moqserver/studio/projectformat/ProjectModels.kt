package com.moqserver.studio.projectformat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hand-written .moqproj types — the ones JSON Schema cannot describe.
 *
 * Everything that mirrors the on-disk/wire contract (ProjectManifest, EndpointDocument,
 * ProjectVariant, the enums, and MoqFormatRules) is generated from `format/schema.json` into
 * ProjectModels.generated.kt at build time. Add schema-shaped fields there by editing the schema,
 * not here.
 */

/** Filesystem layout constants for a .moqproj bundle. Not part of the document schema. */
object MoqProjectFormat {
    /** Current format version supported by this codec/validator. */
    const val FORMAT_VERSION = MoqFormatRules.FORMAT_VERSION

    /** Manifest filename within a .moqproj bundle. */
    const val MANIFEST_FILE = "project.yml"

    /** Directory containing endpoint YAML files. */
    const val ENDPOINTS_DIR = "endpoints"

    /** Directory containing body fixture files. */
    const val FIXTURES_DIR = "fixtures"

    /** Prefix for fixture response files inside the fixtures directory. */
    const val FIXTURE_RESPONSES_PREFIX = "fixtures/responses/"

    /** Accepted YAML file extensions. */
    val YAML_EXTENSIONS = setOf("yml", "yaml")

    /** The standard GraphQL endpoint path. */
    const val GRAPHQL_PATH = "/graphql"

    /** HTTP header for content type. */
    const val CONTENT_TYPE_HEADER = "Content-Type"
}

/**
 * A loaded .moqproj project — the aggregate root for the project format.
 *
 * Not schema-derived: a project is a directory of documents plus the path it was loaded from,
 * which the document schema has no way to express. `@Serializable` — matching
 * `MoqCore.MoqProject`'s `Codable` conformance and `project_path` key — so a whole in-memory
 * project can travel as one JSON payload to `moq-format`'s stateless `validate` call.
 */
@Serializable
data class MoqProject(
    val manifest: ProjectManifest,
    val endpoints: List<EndpointDocument>,
    @SerialName("project_path")
    val projectPath: String,
)
