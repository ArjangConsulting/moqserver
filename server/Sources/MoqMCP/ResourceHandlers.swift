import Foundation
import MCP
import MoqCore
import MoqService

func registerResourceHandlers(on server: Server, service: MoqService, handle: String) {
    Task {
        await server.withMethodHandler(ListResources.self) { _ in
            .init(resources: moqResources)
        }
        await server.withMethodHandler(ReadResource.self) { params in
            try await readResource(uri: params.uri, service: service, handle: handle)
        }
    }
}

private let moqResources: [Resource] = [
    Resource(
        name: "moqproj JSON Schema", uri: "moq://schema/moqproj.json",
        description: "The canonical JSON Schema for .moqproj YAML documents.", mimeType: "application/json"),
    Resource(
        name: "Authoring rules", uri: "moq://docs/authoring-rules",
        description:
            "Format constraints not fully expressible in the JSON Schema: id patterns, reserved paths, enum values.",
        mimeType: "text/markdown"),
    Resource(
        name: "Current project", uri: "moq://project/current",
        description: "A live snapshot of the project currently open in this session.", mimeType: "application/json"),
]

private func readResource(uri: String, service: MoqService, handle: String) async throws -> ReadResource.Result {
    switch uri {
    case "moq://schema/moqproj.json":
        return .init(contents: [.text(schemaJSON(), uri: uri, mimeType: "application/json")])
    case "moq://docs/authoring-rules":
        return .init(contents: [.text(authoringRulesMarkdown(), uri: uri, mimeType: "text/markdown")])
    case "moq://project/current":
        return try await .init(contents: [
            .text(currentProjectJSON(service: service, handle: handle), uri: uri, mimeType: "application/json")
        ])
    default:
        throw MCPError.invalidParams("Unknown resource: \(uri)")
    }
}

private func schemaJSON() -> String {
    guard let url = Bundle.module.url(forResource: "schema", withExtension: "json"),
        let content = try? String(contentsOf: url, encoding: .utf8)
    else {
        return "{}"
    }
    return content
}

private func currentProjectJSON(service: MoqService, handle: String) async throws -> String {
    let project = try await service.projectSnapshot(handle: handle)
    let data = try JSONEncoder().encode(project.endpoints)
    let endpointsJSON = String(data: data, encoding: .utf8) ?? "[]"
    return """
        {"name":\(jsonString(project.manifest.name)),"path":\(jsonString(project.projectPath)),"endpoints":\(endpointsJSON)}
        """
}

private func jsonString(_ value: String) -> String {
    let data = (try? JSONEncoder().encode(value)) ?? Data("\"\"".utf8)
    return String(data: data, encoding: .utf8) ?? "\"\""
}

/// Generated from live `MoqFormatRules`/`MatchType`/`ProjectAuthConfig.AuthType` values rather
/// than hand-copied, so this resource can't silently drift from what the validator actually
/// enforces.
private func authoringRulesMarkdown() -> String {
    let methods = MoqFormatRules.supportedMethods.sorted().joined(separator: ", ")
    let matchTypes = MatchType.allCases.map(\.rawValue).sorted().joined(separator: ", ")
    let authTypes = ProjectAuthConfig.AuthType.allCases.map(\.rawValue).sorted().joined(separator: ", ")

    return """
        # moqproj authoring rules

        - Format version: `\(MoqFormatRules.formatVersion)`
        - Endpoint id pattern: `\(MoqFormatRules.endpointIDPattern)` (lowercase alphanumeric, hyphens, must not start with a hyphen)
        - Supported HTTP methods: \(methods)
        - Reserved paths (rejected): `/health`, `/_admin`, `/_admin/*`, `/_auth`, `/_auth/*`
        - At most one variant per endpoint may have `default: true`; moq_upsert_variant clears siblings automatically. An endpoint with variants but no `default: true` gets a `W_NO_DEFAULT_VARIANT` warning — selection then falls back to declaration order
        - Variant names are unique **case-insensitively** (`Success` and `success` collide). moq_upsert_variant with an existing name — in any casing — replaces that variant in place and its response says `Replaced`; do not follow up with moq_remove_variant for the old spelling
        - Every mutation tool (moq_upsert_endpoint, moq_remove_endpoint, moq_upsert_variant, moq_remove_variant) fails with `E_PROJECT_CHANGED` if the bundle was edited on disk (by hand, or by another session) since it was opened — reopen with moq_open_project rather than retrying the same edit
        - A variant's `body` and `body_file` are mutually exclusive — moq_upsert_variant only accepts inline `body` and manages fixture files itself
        - `request_match` (on a variant) must define at least one of `query`, `headers`, or `body_contains` when present
        - GraphQL endpoints use `path: /graphql` and require `operation.type` plus `operation.name` and/or `operation.document`; multiple endpoints may share `/graphql`, disambiguated by `operation`
        - `auth.type` values: \(authTypes) — `header_name` is required for `api-key` and `header`
        - `request_rules`/`variant.request_match` matcher `match_type` values: \(matchTypes)
        - Variant `status` must be 100-599
        - Variant `call_count` (integer, >= 1 when present) makes that variant eligible only on the Nth call to its endpoint; call_count values must be unique per endpoint
        - Endpoint `strict_call_count: true` (requires at least one variant with `call_count` set) rejects a call with no exact `call_count` match (409 `call_count_exceeded`) instead of falling back to normal selection; default `false`
        """
}
