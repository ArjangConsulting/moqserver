import Foundation
import MCP
import MoqCore
import MoqFormat

/// Builds and returns an MCP `Server` wired with every `moq_*` tool and `moq://` resource.
/// Callers (`MoqMCPRun`, and future `moqserver mcp` if that's added) just need to start it on a
/// transport: `try await server.start(transport: StdioTransport())`.
public func makeMoqMCPServer() -> Server {
    let session = ProjectSession()

    let server = Server(
        name: "moqserver-mcp",
        version: "0.1.0",
        instructions: """
            Author .moqproj mock-API bundles for moqserver. Typical flow: moq_create_project or \
            moq_open_project, then moq_upsert_endpoint + moq_upsert_variant to build out \
            endpoints, moq_validate_project to check for problems, moq_save_project to persist. \
            Read moq://docs/authoring-rules first for the format's constraints (id pattern, \
            reserved paths, supported methods, one-default-variant rule, etc).
            """,
        capabilities: Server.Capabilities(
            resources: .init(),
            tools: .init()
        )
    )

    registerResourceHandlers(on: server, session: session)
    registerToolHandlers(on: server, session: session)

    return server
}

// MARK: - Tool list

let moqTools: [Tool] = [
    Tool(
        name: "moq_create_project",
        description: "Creates a new .moqproj bundle with an empty endpoints/ directory and opens it in this session.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "path": ["type": "string", "description": "Absolute path ending in .moqproj"],
                "name": ["type": "string"],
                "description": ["type": "string"],
                "force": [
                    "type": "boolean",
                    "description": "Discard unsaved changes in the currently open project, if any.",
                ],
            ],
            "required": ["path", "name"],
        ])
    ),
    Tool(
        name: "moq_open_project",
        description: "Opens an existing .moqproj bundle in this session, replacing any currently open project.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "path": ["type": "string"],
                "force": [
                    "type": "boolean", "description": "Discard unsaved changes in the currently open project, if any.",
                ],
            ],
            "required": ["path"],
        ])
    ),
    Tool(
        name: "moq_describe_project",
        description: "Returns the manifest, endpoint count, and dirty/save state of the currently open project.",
        inputSchema: .object(["type": "object", "properties": [:]])
    ),
    Tool(
        name: "moq_list_endpoints",
        description: "Lists endpoints in the open project, optionally filtered by exact path, method, or tag.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "filter_path": ["type": "string"],
                "filter_method": ["type": "string"],
                "filter_tag": ["type": "string"],
            ],
        ])
    ),
    Tool(
        name: "moq_get_endpoint",
        description: "Returns the full EndpointDocument (including all variants) for one endpoint id.",
        inputSchema: .object([
            "type": "object",
            "properties": ["id": ["type": "string"]],
            "required": ["id"],
        ])
    ),
    Tool(
        name: "moq_suggest_endpoint_id",
        description:
            "Derives the endpoint id, alias, and reference_name moqserver would assign for a given method+path, without creating anything. Use this before moq_upsert_endpoint to avoid guessing at the id pattern.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "method": ["type": "string"],
                "path": ["type": "string"],
                "alias": ["type": "string", "description": "Preferred alias; falls back to a derived one if omitted."],
            ],
            "required": ["method", "path"],
        ])
    ),
    Tool(
        name: "moq_upsert_endpoint",
        description: """
            Creates or updates an endpoint's metadata (method, path, alias, description, \
            reference_name, tags, auth, request_rules, operation, network). Existing variants \
            are preserved untouched — manage them with moq_upsert_variant / moq_remove_variant. \
            id must match ^[a-z0-9][a-z0-9-]*$. Reserved paths (/health, /_admin*, /_auth*) are \
            rejected. GraphQL endpoints (path /graphql) require operation.type and either \
            operation.name or operation.document; multiple endpoints may share path /graphql, \
            disambiguated by operation.
            """,
        inputSchema: .object([
            "type": "object",
            "properties": [
                "id": ["type": "string"],
                "alias": ["type": "string"],
                "description": ["type": "string"],
                "reference_name": ["type": "string"],
                "method": ["type": "string", "description": "GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS"],
                "path": ["type": "string"],
                "tags": ["type": "array", "items": ["type": "string"]],
                "auth": [
                    "type": "object",
                    "description": "{ type: none|bearer|basic|api-key|header, verify: bool, header_name?: string }",
                ],
                "request_rules": [
                    "type": "object",
                    "description":
                        "{ headers?, query_params?, cookies?: [{name, match?, required?, match_type?}], verify_cookies? }",
                ],
                "operation": [
                    "type": "object", "description": "{ type: query|mutation|subscription, name?, document? }",
                ],
                "network": ["type": "object", "description": "{ latency_ms?, jitter_ms?, packet_loss_percent? }"],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["id", "method", "path"],
        ])
    ),
    Tool(
        name: "moq_remove_endpoint",
        description: "Removes an endpoint (and all its variants) from the open project.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "id": ["type": "string"],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["id"],
        ])
    ),
    Tool(
        name: "moq_upsert_variant",
        description: """
            Creates or replaces (by name) a response variant on an endpoint. Setting default: \
            true clears the default flag on every sibling variant, so at most one default can \
            ever exist. Provide body as inline JSON/text — never body_file; the store \
            externalizes it to a fixture file at save time and manages that path itself. status \
            must be 100-599.
            """,
        inputSchema: .object([
            "type": "object",
            "properties": [
                "endpoint_id": ["type": "string"],
                "name": ["type": "string"],
                "reference_name": ["type": "string"],
                "description": ["type": "string"],
                "default": ["type": "boolean"],
                "status": ["type": "integer"],
                "headers": ["type": "object", "additionalProperties": ["type": "string"]],
                "request_match": [
                    "type": "object",
                    "description":
                        "{ query?, headers?: {name: value}, body_contains? } — at least one required if present",
                ],
                "body": ["description": "Inline JSON value or string; do not set body_file"],
                "delay_ms": ["type": "integer"],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["endpoint_id", "name", "status"],
        ])
    ),
    Tool(
        name: "moq_remove_variant",
        description: "Removes one named variant from an endpoint.",
        inputSchema: .object([
            "type": "object",
            "properties": [
                "endpoint_id": ["type": "string"],
                "name": ["type": "string"],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["endpoint_id", "name"],
        ])
    ),
    Tool(
        name: "moq_validate_project",
        description:
            "Runs full semantic validation on the open project and returns every diagnostic (errors and warnings).",
        inputSchema: .object(["type": "object", "properties": [:]])
    ),
    Tool(
        name: "moq_save_project",
        description:
            "Persists the in-memory project to disk (crash-recoverable staged replacement). Fails if the bundle changed on disk since it was last loaded or saved.",
        inputSchema: .object(["type": "object", "properties": [:]])
    ),
    Tool(
        name: "moq_import_har",
        description: """
            Parses a HAR 1.2 file (from disk) and merges its endpoints into the currently open \
            project (create or open one first). Sensitive values (auth headers, cookies, \
            tokens, JWT signatures) are redacted before anything reaches the project. New \
            endpoints are appended; endpoints that already exist (same method+path) are updated \
            per the policy flags below but never lose existing variants, request rules, or \
            aliases the spec doesn't mention.
            """,
        inputSchema: .object([
            "type": "object",
            "properties": [
                "path": ["type": "string", "description": "Path to a .har file on disk"],
                "accept_paths": [
                    "type": "array", "items": ["type": "string"],
                    "description": "Only import endpoints at these exact paths; omit to accept all",
                ],
                "update_details": [
                    "type": "boolean", "default": true,
                    "description": "Update auth/request_rules/tags on already-existing endpoints",
                ],
                "replace_existing_bodies": [
                    "type": "boolean", "default": false,
                    "description":
                        "Overwrite an existing variant's body when the spec has a response at the same status code",
                ],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["path"],
        ])
    ),
    Tool(
        name: "moq_import_openapi",
        description: """
            Parses an OpenAPI 3.x spec (YAML/JSON, from a file path or URL) and merges its \
            endpoints into the currently open project, same merge semantics as moq_import_har. \
            Swagger 2.0 is not supported. Fetching from a URL is disabled by default; the \
            operator must set MOQ_MCP_ALLOW_NETWORK=1 on the server process.
            """,
        inputSchema: .object([
            "type": "object",
            "properties": [
                "source": [
                    "type": "string", "description": "A file path, or an http(s) URL if network import is enabled",
                ],
                "auth": [
                    "type": "object",
                    "description":
                        "Only used for a URL source: { bearer: string } or { basic: { username, password } } or { header: { name, value } }",
                ],
                "accept_paths": ["type": "array", "items": ["type": "string"]],
                "accept_tags": ["type": "array", "items": ["type": "string"]],
                "update_details": ["type": "boolean", "default": true],
                "replace_existing_bodies": ["type": "boolean", "default": false],
                "autosave": ["type": "boolean", "default": true],
            ],
            "required": ["source"],
        ])
    ),
]
