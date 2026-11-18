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
        return .init(contents: [.text(AuthoringReference.schemaJSON, uri: uri, mimeType: "application/json")])
    case "moq://docs/authoring-rules":
        return .init(contents: [.text(AuthoringReference.rulesMarkdown(), uri: uri, mimeType: "text/markdown")])
    case "moq://project/current":
        return try await .init(contents: [
            .text(currentProjectJSON(service: service, handle: handle), uri: uri, mimeType: "application/json")
        ])
    default:
        throw MCPError.invalidParams("Unknown resource: \(uri)")
    }
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
