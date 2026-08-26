import Foundation
import MCP
import MoqCore
import MoqService

/// Thin transport adapter: decode MCP tool arguments into `MoqService`'s own input types, call
/// the service, encode its result back into a `CallTool.Result`. No project mutation, validation,
/// or import logic lives here — that's all in `MoqService`, shared with the `moq-format` JSON-RPC
/// adapter. `moq-mcp` runs one implicit session per stdio process, so `handle` is fixed for the
/// process's lifetime rather than something a tool call ever specifies.
func registerToolHandlers(on server: Server, service: MoqService, handle: String) {
    Task {
        await server.withMethodHandler(ListTools.self) { _ in
            .init(tools: moqTools)
        }
        await server.withMethodHandler(CallTool.self) { params in
            do {
                return try await dispatch(params, service: service, handle: handle)
            } catch let error as MCPError {
                throw error
            } catch {
                return try toolError(error)
            }
        }
    }
}

private func dispatch(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    switch params.name {
    case "moq_create_project":
        return try await handleCreateProject(params, service: service, handle: handle)
    case "moq_open_project":
        return try await handleOpenProject(params, service: service, handle: handle)
    case "moq_describe_project":
        return try await handleDescribeProject(service: service, handle: handle)
    case "moq_list_endpoints":
        return try await handleListEndpoints(params, service: service, handle: handle)
    case "moq_get_endpoint":
        return try await handleGetEndpoint(params, service: service, handle: handle)
    case "moq_suggest_endpoint_id":
        return try handleSuggestEndpointID(params, service: service)
    case "moq_upsert_endpoint":
        return try await handleUpsertEndpoint(params, service: service, handle: handle)
    case "moq_remove_endpoint":
        return try await handleRemoveEndpoint(params, service: service, handle: handle)
    case "moq_upsert_variant":
        return try await handleUpsertVariant(params, service: service, handle: handle)
    case "moq_remove_variant":
        return try await handleRemoveVariant(params, service: service, handle: handle)
    case "moq_validate_project":
        return try await handleValidateProject(service: service, handle: handle)
    case "moq_save_project":
        return try await handleSaveProject(service: service, handle: handle)
    case "moq_import_har":
        return try await handleImportHAR(params, service: service, handle: handle)
    case "moq_import_openapi":
        return try await handleImportOpenAPI(params, service: service, handle: handle)
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

private func handleCreateProject(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(CreateProjectInput.self, from: params.arguments)
        let manifest = ProjectManifest(
            name: input.name, description: input.description,
            defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior())
        )
        let description = try await service.createProject(
            handle: handle, manifest: manifest, path: input.path, force: input.force ?? false)
        return try describeProjectResult(description)
    } catch {
        return try toolError(error)
    }
}

private struct OpenProjectInput: Decodable {
    let path: String
    let force: Bool?
}

private func handleOpenProject(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(OpenProjectInput.self, from: params.arguments)
        let description = try await service.openProject(handle: handle, path: input.path, force: input.force ?? false)
        return try describeProjectResult(description)
    } catch {
        return try toolError(error)
    }
}

private func handleDescribeProject(service: MoqService, handle: String) async throws -> CallTool.Result {
    do {
        return try describeProjectResult(try await service.describeProject(handle: handle))
    } catch {
        return try toolError(error)
    }
}

private func describeProjectResult(_ description: ProjectDescription) throws -> CallTool.Result {
    try CallTool.Result(
        content: [
            .text(
                text: "Project '\(description.name)': \(description.endpointCount) endpoint(s)", annotations: nil,
                _meta: nil)
        ],
        structuredContent: description)
}

// MARK: - Endpoint listing / reading

private func handleListEndpoints(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let filter: EndpointFilter
        if let arguments = params.arguments, !arguments.isEmpty {
            filter = try decodeArguments(EndpointFilter.self, from: arguments)
        } else {
            filter = EndpointFilter()
        }
        let summaries = try await service.listEndpoints(handle: handle, filter: filter)
        return try CallTool.Result(
            content: [.text(text: "\(summaries.count) endpoint(s)", annotations: nil, _meta: nil)],
            structuredContent: EndpointListResult(endpoints: summaries))
    } catch {
        return try toolError(error)
    }
}

private struct EndpointIDInput: Decodable {
    let id: String
}

private func handleGetEndpoint(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(EndpointIDInput.self, from: params.arguments)
        let endpoint = try await service.getEndpoint(handle: handle, id: input.id)
        return try CallTool.Result(
            content: [.text(text: "Endpoint \(endpoint.id)", annotations: nil, _meta: nil)], structuredContent: endpoint
        )
    } catch {
        return try toolError(error)
    }
}

// MARK: - Endpoint naming helper (no session required)

private func handleSuggestEndpointID(_ params: CallTool.Parameters, service: MoqService) throws -> CallTool.Result {
    let input = try decodeArguments(SuggestEndpointIDInput.self, from: params.arguments)
    let result = service.suggestEndpointID(input)
    return try CallTool.Result(
        content: [.text(text: result.id, annotations: nil, _meta: nil)], structuredContent: result)
}

// MARK: - Endpoint mutation

private func handleUpsertEndpoint(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws -> CallTool.Result {
    do {
        let input = try decodeArguments(EndpointUpsertInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        let document = try await service.upsertEndpoint(handle: handle, input: input, autosave: autosave)
        return try CallTool.Result(
            content: [.text(text: "Upserted endpoint \(input.id)", annotations: nil, _meta: nil)],
            structuredContent: document)
    } catch {
        return try toolError(error)
    }
}

private func handleRemoveEndpoint(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws -> CallTool.Result {
    do {
        let input = try decodeArguments(EndpointIDInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        try await service.removeEndpoint(handle: handle, id: input.id, autosave: autosave)
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

private func handleUpsertVariant(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let ref = try decodeArguments(VariantEndpointRef.self, from: params.arguments)
        let variant = try decodeArguments(ProjectVariant.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        try await service.upsertVariant(
            handle: handle, endpointID: ref.endpointId, variant: variant, autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(text: "Upserted variant \(variant.name) on \(ref.endpointId)", annotations: nil, _meta: nil)
            ],
            structuredContent: variant)
    } catch {
        return try toolError(error)
    }
}

private func handleRemoveVariant(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(RemoveVariantInput.self, from: params.arguments)
        let autosave = try autosaveFlag(from: params.arguments)
        try await service.removeVariant(handle: handle, input: input, autosave: autosave)
        return try CallTool.Result(
            content: [
                .text(
                    text: "Removed variant \(input.name) from \(input.endpointId)", annotations: nil, _meta: nil)
            ])
    } catch {
        return try toolError(error)
    }
}

// MARK: - Validation / save

private func handleValidateProject(service: MoqService, handle: String) async throws -> CallTool.Result {
    do {
        let result = try await service.validateProject(handle: handle)
        return try CallTool.Result(
            content: [
                .text(
                    text: "\(result.errorCount) error(s), \(result.warningCount) warning(s)", annotations: nil,
                    _meta: nil)
            ],
            structuredContent: result)
    } catch {
        return try toolError(error)
    }
}

private func handleSaveProject(service: MoqService, handle: String) async throws -> CallTool.Result {
    do {
        try await service.saveProject(handle: handle)
        return try CallTool.Result(content: [.text(text: "Saved", annotations: nil, _meta: nil)])
    } catch {
        return try toolError(error)
    }
}

// MARK: - Import

private func handleImportHAR(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(ImportHARInput.self, from: params.arguments)
        let common = try decodeArguments(ImportInputCommon.self, from: params.arguments ?? [:])
        let autosave = try autosaveFlag(from: params.arguments)
        let summary = try await service.importHAR(handle: handle, input: input, common: common, autosave: autosave)
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

private func handleImportOpenAPI(
    _ params: CallTool.Parameters, service: MoqService, handle: String
) async throws
    -> CallTool.Result
{
    do {
        let input = try decodeArguments(ImportOpenAPIInput.self, from: params.arguments)
        let common = try decodeArguments(ImportInputCommon.self, from: params.arguments ?? [:])
        let autosave = try autosaveFlag(from: params.arguments)
        let summary = try await service.importOpenAPI(handle: handle, input: input, common: common, autosave: autosave)
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
