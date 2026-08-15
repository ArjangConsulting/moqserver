import Foundation
import MCP
import MoqCore
import MoqFormat
import MoqImport

func registerToolHandlers(on server: Server, session: ProjectSession) {
    Task {
        await server.withMethodHandler(ListTools.self) { _ in
            .init(tools: moqTools)
        }
        await server.withMethodHandler(CallTool.self) { params in
            do {
                return try await dispatch(params, session: session)
            } catch let error as MCPError {
                throw error
            } catch {
                return try toolError(error)
            }
        }
    }
}

private func dispatch(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result {
    switch params.name {
    case "moq_create_project":
        return try await handleCreateProject(params, session: session)
    case "moq_open_project":
        return try await handleOpenProject(params, session: session)
    case "moq_describe_project":
        return try await handleDescribeProject(session: session)
    case "moq_list_endpoints":
        return try await handleListEndpoints(params, session: session)
    case "moq_get_endpoint":
        return try await handleGetEndpoint(params, session: session)
    case "moq_suggest_endpoint_id":
        return try handleSuggestEndpointID(params)
    case "moq_upsert_endpoint":
        return try await handleUpsertEndpoint(params, session: session)
    case "moq_remove_endpoint":
        return try await handleRemoveEndpoint(params, session: session)
    case "moq_upsert_variant":
        return try await handleUpsertVariant(params, session: session)
    case "moq_remove_variant":
        return try await handleRemoveVariant(params, session: session)
    case "moq_validate_project":
        return try await handleValidateProject(session: session)
    case "moq_save_project":
        return try await handleSaveProject(session: session)
    case "moq_import_har":
        return try await handleImportHAR(params, session: session)
    case "moq_import_openapi":
        return try await handleImportOpenAPI(params, session: session)
    default:
        throw MCPError.methodNotFound("Unknown tool: \(params.name)")
    }
}

// MARK: - Project lifecycle

private struct CreateProjectInput: Decodable {
    let path: String
    let name: String
    let description: String?
    let force: Bool?
}

private func handleCreateProject(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result
{
    let input = try decodeArguments(CreateProjectInput.self, from: params.arguments)
    let manifest = ProjectManifest(
        name: input.name, description: input.description,
        defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
    do {
        try await session.create(manifest: manifest, at: input.path, force: input.force ?? false)
    } catch {
        return try toolError(error)
    }
    return try await describeProjectResult(session: session)
}

private struct OpenProjectInput: Decodable {
    let path: String
    let force: Bool?
}

private func handleOpenProject(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result {
    let input = try decodeArguments(OpenProjectInput.self, from: params.arguments)
    do {
        try await session.open(path: input.path, force: input.force ?? false)
    } catch {
        return try toolError(error)
    }
    return try await describeProjectResult(session: session)
}

private func handleDescribeProject(session: ProjectSession) async throws -> CallTool.Result {
    try await describeProjectResult(session: session)
}

private struct ProjectDescription: Codable {
    let name: String
    let description: String?
    let path: String
    let endpointCount: Int
    let dirty: Bool

    enum CodingKeys: String, CodingKey {
        case name, description, path
        case endpointCount = "endpoint_count"
        case dirty
    }
}

private func describeProjectResult(session: ProjectSession) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let project = await store.currentProject
        let dirty = await session.isDirty
        let description = ProjectDescription(
            name: project.manifest.name, description: project.manifest.description, path: project.projectPath,
            endpointCount: project.endpoints.count, dirty: dirty)
        return try CallTool.Result(
            content: [
                .text(
                    text: "Project '\(project.manifest.name)': \(project.endpoints.count) endpoint(s)",
                    annotations: nil, _meta: nil)
            ],
            structuredContent: description)
    } catch {
        return try toolError(error)
    }
}

// MARK: - Endpoint listing / reading

private struct ListEndpointsInput: Decodable {
    let filterPath: String?
    let filterMethod: String?
    let filterTag: String?

    enum CodingKeys: String, CodingKey {
        case filterPath = "filter_path"
        case filterMethod = "filter_method"
        case filterTag = "filter_tag"
    }
}

private struct EndpointSummary: Codable {
    let id: String
    let method: String
    let path: String
    let alias: String?
    let referenceName: String
    let variantCount: Int
    let defaultVariant: String?

    enum CodingKeys: String, CodingKey {
        case id, method, path, alias
        case referenceName = "reference_name"
        case variantCount = "variant_count"
        case defaultVariant = "default_variant"
    }
}

private func handleListEndpoints(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result
{
    do {
        let store = try await session.currentStore()
        let project = await store.currentProject
        let input: ListEndpointsInput
        if let arguments = params.arguments, !arguments.isEmpty {
            input = try decodeArguments(ListEndpointsInput.self, from: arguments)
        } else {
            input = ListEndpointsInput(filterPath: nil, filterMethod: nil, filterTag: nil)
        }

        let filtered = project.endpoints.filter { endpoint in
            if let filterPath = input.filterPath, endpoint.path != filterPath { return false }
            if let filterMethod = input.filterMethod, endpoint.method.uppercased() != filterMethod.uppercased() {
                return false
            }
            if let filterTag = input.filterTag, !(endpoint.tags ?? []).contains(filterTag) { return false }
            return true
        }
        let summaries = filtered.map { endpoint in
            EndpointSummary(
                id: endpoint.id, method: endpoint.method, path: endpoint.path, alias: endpoint.alias,
                referenceName: endpoint.referenceName, variantCount: endpoint.variants.count,
                defaultVariant: endpoint.variants.first { $0.isDefault == true }?.name)
        }
        return try CallTool.Result(
            content: [.text(text: "\(summaries.count) endpoint(s)", annotations: nil, _meta: nil)],
            structuredContent: summaries)
    } catch {
        return try toolError(error)
    }
}

private struct EndpointIDInput: Decodable {
    let id: String
}

private func handleGetEndpoint(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(EndpointIDInput.self, from: params.arguments)
        let project = await store.currentProject
        guard let endpoint = project.endpoints.first(where: { $0.id == input.id }) else {
            return try toolError(code: "E_ENDPOINT_NOT_FOUND", message: "No endpoint with id: \(input.id)")
        }
        return try CallTool.Result(
            content: [.text(text: "Endpoint \(endpoint.id)", annotations: nil, _meta: nil)], structuredContent: endpoint
        )
    } catch {
        return try toolError(error)
    }
}

// MARK: - Endpoint naming helper (no session required)

private struct SuggestEndpointIDInput: Decodable {
    let method: String
    let path: String
    let alias: String?
}

private struct SuggestedEndpointIdentity: Codable {
    let id: String
    let alias: String
    let referenceName: String

    enum CodingKeys: String, CodingKey {
        case id, alias
        case referenceName = "reference_name"
    }
}

private func handleSuggestEndpointID(_ params: CallTool.Parameters) throws -> CallTool.Result {
    let input = try decodeArguments(SuggestEndpointIDInput.self, from: params.arguments)
    // Mirrors MoqImport's ImportConverter.endpointID derivation, without pulling in a MoqImport
    // dependency for one function.
    var normalized = input.path
    if normalized.hasPrefix("/") { normalized.removeFirst() }
    normalized = normalized.replacingOccurrences(of: #"\{[^}]+\}"#, with: "param", options: .regularExpression)
    normalized = normalized.replacingOccurrences(of: "[^a-zA-Z0-9/]", with: "", options: .regularExpression)
    normalized = normalized.replacingOccurrences(of: "/", with: "-").lowercased()
    while normalized.hasSuffix("-") { normalized.removeLast() }
    let id = normalized.isEmpty ? "\(input.method.lowercased())-root" : "\(input.method.lowercased())-\(normalized)"

    let alias =
        input.alias?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        ? input.alias! : EndpointAlias.defaultAlias(method: input.method, path: input.path)
    let referenceName = suggestedEndpointReferenceName(preferredSource: alias, fallbackID: id)

    let result = SuggestedEndpointIdentity(id: id, alias: alias, referenceName: referenceName)
    return try CallTool.Result(content: [.text(text: id, annotations: nil, _meta: nil)], structuredContent: result)
}

// MARK: - Endpoint mutation

private func handleUpsertEndpoint(
    _ params: CallTool.Parameters, session: ProjectSession
) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(EndpointUpsertInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)

        if MoqFormatRules.isReservedPath(input.path) {
            return try toolError(
                code: "E_RESERVED_PATH",
                message: "Path \"\(input.path)\" is reserved and cannot be used by mock endpoints.")
        }

        let project = await store.currentProject
        let existingVariants = project.endpoints.first(where: { $0.id == input.id })?.variants ?? []
        let document = input.makeDocument(existingVariants: existingVariants)

        if project.endpoints.contains(where: { $0.id == input.id }) {
            try await store.updateEndpoint(id: input.id, document)
        } else {
            try await store.addEndpoint(document)
        }
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(
            content: [.text(text: "Upserted endpoint \(input.id)", annotations: nil, _meta: nil)],
            structuredContent: document)
    } catch {
        return try toolError(error)
    }
}

private func handleRemoveEndpoint(
    _ params: CallTool.Parameters, session: ProjectSession
) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(EndpointIDInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        try await store.removeEndpoint(id: input.id)
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(content: [.text(text: "Removed endpoint \(input.id)", annotations: nil, _meta: nil)])
    } catch {
        return try toolError(error)
    }
}

// MARK: - Variant mutation

private struct VariantEndpointRef: Decodable {
    let endpointId: String
    enum CodingKeys: String, CodingKey { case endpointId = "endpoint_id" }
}

private func handleUpsertVariant(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result
{
    do {
        let store = try await session.currentStore()
        let ref = try decodeArguments(VariantEndpointRef.self, from: params.arguments)
        let variant = try decodeArguments(ProjectVariant.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)

        try await store.upsertVariant(endpointID: ref.endpointId, variant)
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(text: "Upserted variant \(variant.name) on \(ref.endpointId)", annotations: nil, _meta: nil)
            ],
            structuredContent: variant)
    } catch {
        return try toolError(error)
    }
}

private struct RemoveVariantInput: Decodable {
    let endpointId: String
    let name: String
    enum CodingKeys: String, CodingKey {
        case endpointId = "endpoint_id"
        case name
    }
}

private func handleRemoveVariant(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result
{
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(RemoveVariantInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        try await store.removeVariant(endpointID: input.endpointId, name: input.name)
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(text: "Removed variant \(input.name) from \(input.endpointId)", annotations: nil, _meta: nil)
            ])
    } catch {
        return try toolError(error)
    }
}

// MARK: - Validation / save

private struct ValidationResult: Codable {
    let errorCount: Int
    let warningCount: Int
    let diagnostics: [DiagnosticPayload]

    enum CodingKeys: String, CodingKey {
        case errorCount = "error_count"
        case warningCount = "warning_count"
        case diagnostics
    }
}

private func handleValidateProject(session: ProjectSession) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let project = await store.currentProject
        let diagnostics = ProjectValidator().validate(project)
        let errors = diagnostics.filter { $0.severity == .error }
        let result = ValidationResult(
            errorCount: errors.count, warningCount: diagnostics.count - errors.count,
            diagnostics: diagnostics.map(DiagnosticPayload.init))
        return try CallTool.Result(
            content: [
                .text(
                    text: "\(errors.count) error(s), \(diagnostics.count - errors.count) warning(s)", annotations: nil,
                    _meta: nil)
            ],
            structuredContent: result, isError: !errors.isEmpty)
    } catch {
        return try toolError(error)
    }
}

private func handleSaveProject(session: ProjectSession) async throws -> CallTool.Result {
    do {
        try await session.save()
        return try CallTool.Result(content: [.text(text: "Saved", annotations: nil, _meta: nil)])
    } catch {
        return try toolError(error)
    }
}

// MARK: - Import

private struct ImportInputCommon: Decodable {
    let acceptPaths: [String]?
    let updateDetails: Bool?
    let replaceExistingBodies: Bool?

    enum CodingKeys: String, CodingKey {
        case acceptPaths = "accept_paths"
        case updateDetails = "update_details"
        case replaceExistingBodies = "replace_existing_bodies"
    }

    var selection: ImportSelection {
        ImportSelection(acceptedPaths: acceptPaths.map(Set.init))
    }
    var policy: ImportMergePolicy {
        ImportMergePolicy(updateDetails: updateDetails ?? true, replaceExistingBodies: replaceExistingBodies ?? false)
    }
}

private struct ImportSummary: Codable {
    let projectEndpointCount: Int
    let newEndpointCount: Int
    let warnings: [String]

    enum CodingKeys: String, CodingKey {
        case projectEndpointCount = "project_endpoint_count"
        case newEndpointCount = "new_endpoint_count"
        case warnings
    }
}

/// Merges `spec` into the session's open project and reconciles the result back into `store`
/// endpoint-by-endpoint (`merge` never removes endpoints, so this is always add-or-update, never
/// remove).
private func applyImport(
    _ spec: ParsedSpec, common: ImportInputCommon, store: ProjectStore
) async throws -> ImportSummary {
    let existing = await store.currentProject
    let existingIDs = Set(existing.endpoints.map(\.id))
    let merged = ImportConverter.merge(spec, selection: common.selection, policy: common.policy, into: existing)

    for endpoint in merged.endpoints {
        if existingIDs.contains(endpoint.id) {
            try await store.updateEndpoint(id: endpoint.id, endpoint)
        } else {
            try await store.addEndpoint(endpoint)
        }
    }

    return ImportSummary(
        projectEndpointCount: merged.endpoints.count,
        newEndpointCount: merged.endpoints.count - existing.endpoints.count,
        warnings: spec.warnings)
}

private struct ImportHARInput: Decodable {
    let path: String
}

private func handleImportHAR(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result {
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(ImportHARInput.self, from: params.arguments)
        let common = try decodeArguments(ImportInputCommon.self, from: params.arguments ?? [:])
        let autosave = try autosaveFlag(from: params.arguments)

        guard let content = try? String(contentsOfFile: input.path, encoding: .utf8) else {
            return try toolError(code: "E_IMPORT_READ_FAILED", message: "Could not read file: \(input.path)")
        }
        let spec: ParsedSpec
        do {
            spec = try HARImporter.parse(content)
        } catch {
            return try toolError(code: "E_IMPORT_PARSE_FAILED", message: "\(error)")
        }

        let summary = try await applyImport(spec, common: common, store: store)
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(
                    text: "Imported \(summary.newEndpointCount) new endpoint(s) from HAR", annotations: nil, _meta: nil)
            ],
            structuredContent: summary)
    } catch {
        return try toolError(error)
    }
}

private struct ImportOpenAPIInput: Decodable {
    let source: String
    let auth: ImportAuthInput?
}

private struct ImportAuthInput: Decodable {
    let bearer: String?
    let basic: BasicAuthInput?
    let header: HeaderAuthInput?

    struct BasicAuthInput: Decodable {
        let username: String
        let password: String
    }
    struct HeaderAuthInput: Decodable {
        let name: String
        let value: String
    }

    var resolved: URLImportAuth? {
        if let bearer { return .bearer(bearer) }
        if let basic { return .basic(username: basic.username, password: basic.password) }
        if let header { return .header(name: header.name, value: header.value) }
        return nil
    }
}

private func handleImportOpenAPI(_ params: CallTool.Parameters, session: ProjectSession) async throws -> CallTool.Result
{
    do {
        let store = try await session.currentStore()
        let input = try decodeArguments(ImportOpenAPIInput.self, from: params.arguments)
        let common = try decodeArguments(ImportInputCommon.self, from: params.arguments ?? [:])
        let autosave = try autosaveFlag(from: params.arguments)

        let content: String
        if input.source.hasPrefix("http://") || input.source.hasPrefix("https://") {
            guard ProcessInfo.processInfo.environment["MOQ_MCP_ALLOW_NETWORK"] == "1" else {
                return try toolError(
                    code: "E_NETWORK_IMPORT_DISABLED",
                    message:
                        "URL import is disabled. Set MOQ_MCP_ALLOW_NETWORK=1 on the server process to enable it, or provide a local file path instead."
                )
            }
            do {
                let fetched = try await SpecFetcher.fetchSpec(from: input.source, auth: input.auth?.resolved)
                content = fetched.content
            } catch {
                return try toolError(code: "E_IMPORT_FETCH_FAILED", message: "\(error)")
            }
        } else {
            guard let fileContent = try? String(contentsOfFile: input.source, encoding: .utf8) else {
                return try toolError(code: "E_IMPORT_READ_FAILED", message: "Could not read file: \(input.source)")
            }
            content = fileContent
        }

        let spec: ParsedSpec
        do {
            spec = try OpenAPIImporter.parse(content)
        } catch {
            return try toolError(code: "E_IMPORT_PARSE_FAILED", message: "\(error)")
        }

        let summary = try await applyImport(spec, common: common, store: store)
        try await session.recordMutation(autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(
                    text: "Imported \(summary.newEndpointCount) new endpoint(s) from OpenAPI", annotations: nil,
                    _meta: nil)
            ],
            structuredContent: summary)
    } catch {
        return try toolError(error)
    }
}

// MARK: - Shared helpers

private struct AutosaveFlag: Decodable {
    let autosave: Bool?
}

private func autosaveFlag(from arguments: [String: Value]?) throws -> Bool {
    guard let arguments, arguments["autosave"] != nil else { return true }
    return try decodeArguments(AutosaveFlag.self, from: arguments).autosave ?? true
}
