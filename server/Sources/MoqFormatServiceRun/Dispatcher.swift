import Foundation
import MoqCore
import MoqService

/// Standard JSON-RPC 2.0 application-error code. Every `moq-format` failure uses this one code;
/// the actual reason is `error.data.code` (`MoqServiceError`'s stable string codes, the same ones
/// `moq-mcp` surfaces), which callers should branch on rather than the JSON-RPC integer.
private let applicationErrorCode = -32000
private let parseErrorCode = -32700
private let methodNotFoundCode = -32601
private let invalidParamsCode = -32602

/// Routes one JSON-RPC request to `MoqService` and returns the response to write back. Decoding
/// and encoding happen here so every method handler below just moves typed values in and out of
/// the service, matching the shape of `MoqMCP`'s tool handlers over the same service.
struct Dispatcher {
    let service: MoqService

    func handle(_ requestData: Data) async -> Data {
        let request: JSONRPCRequest
        do {
            request = try JSONDecoder().decode(JSONRPCRequest.self, from: requestData)
        } catch {
            return encode(JSONRPCResponse(id: nil, error: .init(code: parseErrorCode, message: "\(error)", data: nil)))
        }

        do {
            let result = try await route(request.method, params: request.params)
            let resultData = try JSONEncoder().encode(result)
            return encode(JSONRPCResponse(id: request.id, resultData: resultData))
        } catch let error as DispatchError {
            return encode(JSONRPCResponse(id: request.id, error: error.errorObject))
        } catch {
            let (code, message) = moqServiceErrorCode(error)
            return encode(
                JSONRPCResponse(
                    id: request.id,
                    error: .init(code: applicationErrorCode, message: message, data: .init(code: code))))
        }
    }

    private func encode(_ response: JSONRPCResponse) -> Data {
        (try? JSONEncoder().encode(response)) ?? Data("{}".utf8)
    }

    private struct DispatchError: Error {
        let errorObject: JSONRPCErrorObject
    }

    private func decode<T: Decodable>(_ type: T.Type, _ params: Data?) throws -> T {
        guard let params else {
            throw DispatchError(
                errorObject: .init(code: invalidParamsCode, message: "Missing params", data: nil))
        }
        do {
            return try JSONDecoder().decode(T.self, from: params)
        } catch {
            throw DispatchError(
                errorObject: .init(code: invalidParamsCode, message: "Invalid params: \(error)", data: nil))
        }
    }

    // MARK: - Method routing

    private func route(_ method: String, params: Data?) async throws -> any Encodable {
        switch method {
        case "service.info":
            return ServiceInfo(protocolVersion: 1, capabilities: ["project-revision", "session-recovery"])
        case "session.open":
            return SessionHandle(handle: await service.openSession())
        case "session.close":
            let input = try decode(HandleOnlyInput.self, params)
            await service.closeSession(input.handle)
            return EmptyResult()

        case "project.create":
            let input = try decode(CreateProjectParams.self, params)
            let manifest = ProjectManifest(
                name: input.name, description: input.description,
                defaults: ProjectDefaults(
                    auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
            return try await service.createProject(
                handle: input.handle, manifest: manifest, path: input.path, force: input.force ?? false)
        case "project.open":
            let input = try decode(OpenProjectParams.self, params)
            return try await service.openProject(handle: input.handle, path: input.path, force: input.force ?? false)
        case "project.describe":
            let input = try decode(HandleOnlyInput.self, params)
            return try await service.describeProject(handle: input.handle)
        case "project.save":
            let input = try decode(HandleOnlyInput.self, params)
            try await service.saveProject(handle: input.handle)
            return EmptyResult()
        case "project.read":
            let input = try decode(HandleOnlyInput.self, params)
            return try await service.projectSnapshot(handle: input.handle)
        case "project.write":
            let input = try decode(WriteProjectParams.self, params)
            return try await service.writeProject(
                handle: input.handle, project: input.project, force: input.force ?? false,
                expectedRevision: input.expectedRevision)
        case "project.validate":
            let input = try decode(HandleOnlyInput.self, params)
            return try await service.validateProject(handle: input.handle)

        case "validate":
            // The stateless entry point: no session required, no project on disk to re-read —
            // this is the surface a caller (Studio) editing an in-memory, unsaved project needs.
            let input = try decode(ValidateProjectParams.self, params)
            return service.validateProject(input.project)

        case "endpoint.list":
            let input = try decode(ListEndpointsParams.self, params)
            return try await service.listEndpoints(handle: input.handle, filter: input.filter)
        case "endpoint.get":
            let input = try decode(EndpointRefParams.self, params)
            return try await service.getEndpoint(handle: input.handle, id: input.id)
        case "endpoint.suggestId":
            let input = try decode(SuggestEndpointIDInput.self, params)
            return service.suggestEndpointID(input)
        case "endpoint.upsert":
            let input = try decode(UpsertEndpointParams.self, params)
            return try await service.upsertEndpoint(
                handle: input.handle, input: input.endpoint, autosave: input.autosave ?? true)
        case "endpoint.remove":
            let input = try decode(EndpointRefParams.self, params)
            try await service.removeEndpoint(handle: input.handle, id: input.id, autosave: input.autosave ?? true)
            return EmptyResult()

        case "variant.upsert":
            let input = try decode(UpsertVariantParams.self, params)
            try await service.upsertVariant(
                handle: input.handle, endpointID: input.endpointId, variant: input.variant,
                autosave: input.autosave ?? true)
            return EmptyResult()
        case "variant.remove":
            let input = try decode(RemoveVariantParams.self, params)
            try await service.removeVariant(
                handle: input.handle, input: input.variantRef, autosave: input.autosave ?? true)
            return EmptyResult()

        case "import.har":
            let input = try decode(ImportHARParams.self, params)
            return try await service.importHAR(
                handle: input.handle, input: input.input, common: input.common ?? ImportInputCommon(),
                autosave: input.autosave ?? true)
        case "import.openapi":
            let input = try decode(ImportOpenAPIParams.self, params)
            return try await service.importOpenAPI(
                handle: input.handle, input: input.input, common: input.common ?? ImportInputCommon(),
                autosave: input.autosave ?? true)
        case "import.parseHar":
            let input = try decode(ParseHARParams.self, params)
            return try service.parseHAR(path: input.path)
        case "import.parseOpenapi":
            let input = try decode(ImportOpenAPIInput.self, params)
            return try await service.parseOpenAPI(source: input.source, auth: input.auth)

        default:
            throw DispatchError(
                errorObject: .init(code: methodNotFoundCode, message: "Unknown method: \(method)", data: nil))
        }
    }
}

// MARK: - Parameter shapes

private struct ServiceInfo: Codable { let protocolVersion: Int; let capabilities: [String] }
private struct SessionHandle: Codable { let handle: String }
private struct EmptyResult: Codable {}
private struct HandleOnlyInput: Decodable { let handle: String }

private struct CreateProjectParams: Decodable {
    let handle: String
    let name: String
    let description: String?
    let path: String
    let force: Bool?
}

private struct OpenProjectParams: Decodable {
    let handle: String
    let path: String
    let force: Bool?
}

private struct ValidateProjectParams: Decodable {
    let project: MoqProject
}

private struct ParseHARParams: Decodable {
    let path: String
}

private struct WriteProjectParams: Decodable {
    let handle: String
    let project: MoqProject
    let force: Bool?
    let expectedRevision: String?

    enum CodingKeys: String, CodingKey {
        case handle, project, force
        case expectedRevision = "expected_revision"
    }
}

private struct ListEndpointsParams: Decodable {
    let handle: String
    let filter: EndpointFilter

    private enum CodingKeys: String, CodingKey {
        case handle
        case filterPath = "filter_path"
        case filterMethod = "filter_method"
        case filterTag = "filter_tag"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        filter = EndpointFilter(
            filterPath: try container.decodeIfPresent(String.self, forKey: .filterPath),
            filterMethod: try container.decodeIfPresent(String.self, forKey: .filterMethod),
            filterTag: try container.decodeIfPresent(String.self, forKey: .filterTag))
    }
}

private struct EndpointRefParams: Decodable {
    let handle: String
    let id: String
    let autosave: Bool?
}

private struct UpsertEndpointParams: Decodable {
    let handle: String
    let autosave: Bool?
    let endpoint: EndpointUpsertInput

    private enum CodingKeys: String, CodingKey { case handle, autosave }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        autosave = try container.decodeIfPresent(Bool.self, forKey: .autosave)
        endpoint = try EndpointUpsertInput(from: decoder)
    }
}

private struct UpsertVariantParams: Decodable {
    let handle: String
    let endpointId: String
    let autosave: Bool?
    let variant: ProjectVariant

    private enum CodingKeys: String, CodingKey {
        case handle, autosave
        case endpointId = "endpoint_id"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        endpointId = try container.decode(String.self, forKey: .endpointId)
        autosave = try container.decodeIfPresent(Bool.self, forKey: .autosave)
        variant = try ProjectVariant(from: decoder)
    }
}

private struct RemoveVariantParams: Decodable {
    let handle: String
    let autosave: Bool?
    let variantRef: RemoveVariantInput

    private enum CodingKeys: String, CodingKey { case handle, autosave }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        autosave = try container.decodeIfPresent(Bool.self, forKey: .autosave)
        variantRef = try RemoveVariantInput(from: decoder)
    }
}

private struct ImportHARParams: Decodable {
    let handle: String
    let autosave: Bool?
    let common: ImportInputCommon?
    let input: ImportHARInput

    private enum CodingKeys: String, CodingKey { case handle, autosave }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        autosave = try container.decodeIfPresent(Bool.self, forKey: .autosave)
        common = try ImportInputCommon(from: decoder)
        input = try ImportHARInput(from: decoder)
    }
}

private struct ImportOpenAPIParams: Decodable {
    let handle: String
    let autosave: Bool?
    let common: ImportInputCommon?
    let input: ImportOpenAPIInput

    private enum CodingKeys: String, CodingKey { case handle, autosave }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        handle = try container.decode(String.self, forKey: .handle)
        autosave = try container.decodeIfPresent(Bool.self, forKey: .autosave)
        common = try ImportInputCommon(from: decoder)
        input = try ImportOpenAPIInput(from: decoder)
    }
}
