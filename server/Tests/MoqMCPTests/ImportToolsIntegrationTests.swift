import Foundation
import MCP
import Testing

@testable import MoqMCP

struct ImportToolsIntegrationTests {
    func connectedClient() async throws -> Client {
        let server = makeMoqMCPServer()
        let (clientTransport, serverTransport) = await InMemoryTransport.createConnectedPair()
        try await server.start(transport: serverTransport)
        let client = Client(name: "test-client", version: "1.0")
        _ = try await client.connect(transport: clientTransport)
        return client
    }

    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString).appendingPathComponent("mcp-import-\(label)-\(UUID().uuidString).moqproj")
    }

    @Test("moq_import_har adds endpoints to the open project and redacts secrets")
    func importsHAR() async throws {
        let projectPath = tempPath("har")
        defer { try? FileManager.default.removeItem(atPath: projectPath) }
        let harPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("import-\(UUID().uuidString).har")
        defer { try? FileManager.default.removeItem(atPath: harPath) }

        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": {
                    "method": "GET", "url": "https://api.test/users", "headers": [
                      { "name": "Authorization", "value": "Bearer super-secret" }
                    ]
                  },
                  "response": {
                    "status": 200, "headers": [],
                    "content": { "mimeType": "application/json", "text": "{\\"users\\":[]}" }
                  }
                }
              ] }
            }
            """
        try har.write(toFile: harPath, atomically: true, encoding: .utf8)

        let client = try await connectedClient()
        _ = try await client.callTool(name: "moq_create_project", arguments: ["path": .string(projectPath), "name": .string("T")])

        let result = try await client.callTool(name: "moq_import_har", arguments: ["path": .string(harPath)])
        #expect(result.isError != true)

        let listed = try await client.callTool(name: "moq_list_endpoints")
        #expect(listed.isError != true)
        if case .text(let text, _, _) = listed.content.first {
            #expect(text.contains("1 endpoint"))
        }

        let endpoint = try await client.callTool(name: "moq_get_endpoint", arguments: ["id": .string("get-users")])
        #expect(endpoint.isError != true)

        let saved = try await client.callTool(name: "moq_save_project")
        #expect(saved.isError != true)

        // The imported HAR request header carried a secret; it must never reach disk.
        let endpointFile = (projectPath as NSString).appendingPathComponent("endpoints/get-users.yml")
        let onDisk = try String(contentsOfFile: endpointFile, encoding: .utf8)
        #expect(!onDisk.contains("super-secret"))
    }

    @Test("moq_import_openapi merges into an existing endpoint without dropping its variants")
    func importOpenAPIMergesWithoutDroppingVariants() async throws {
        let projectPath = tempPath("openapi-merge")
        defer { try? FileManager.default.removeItem(atPath: projectPath) }
        let specPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("import-\(UUID().uuidString).yaml")
        defer { try? FileManager.default.removeItem(atPath: specPath) }

        let spec = """
            openapi: 3.0.3
            info:
              title: Users API
              version: "1.0"
            paths:
              /users:
                get:
                  summary: List users
                  responses:
                    "200":
                      description: OK
            """
        try spec.write(toFile: specPath, atomically: true, encoding: .utf8)

        let client = try await connectedClient()
        _ = try await client.callTool(name: "moq_create_project", arguments: ["path": .string(projectPath), "name": .string("T")])
        _ = try await client.callTool(
            name: "moq_upsert_endpoint",
            arguments: ["id": .string("get-users"), "method": .string("GET"), "path": .string("/users")])
        _ = try await client.callTool(
            name: "moq_upsert_variant",
            arguments: [
                "endpoint_id": .string("get-users"), "name": .string("hand-authored"), "status": .int(404),
            ])

        let result = try await client.callTool(name: "moq_import_openapi", arguments: ["source": .string(specPath)])
        #expect(result.isError != true)

        let saved = try await client.callTool(name: "moq_save_project")
        #expect(saved.isError != true)

        // The hand-authored 404 variant must survive the import merge, and the import's own
        // 200 response must have been added alongside it (not replacing it).
        let endpointFile = (projectPath as NSString).appendingPathComponent("endpoints/get-users.yml")
        let onDisk = try String(contentsOfFile: endpointFile, encoding: .utf8)
        #expect(onDisk.contains("hand-authored"))
        #expect(onDisk.contains("status: 404"))
        #expect(onDisk.contains("status: 200"))
    }

    @Test("moq_import_openapi rejects URL sources when MOQ_MCP_ALLOW_NETWORK is not set")
    func rejectsURLImportByDefault() async throws {
        let projectPath = tempPath("url-disabled")
        defer { try? FileManager.default.removeItem(atPath: projectPath) }
        let client = try await connectedClient()
        _ = try await client.callTool(name: "moq_create_project", arguments: ["path": .string(projectPath), "name": .string("T")])

        let result = try await client.callTool(
            name: "moq_import_openapi", arguments: ["source": .string("https://example.com/openapi.json")])
        #expect(result.isError == true)
        if case .text(let text, _, _) = result.content.first {
            #expect(text.contains("E_NETWORK_IMPORT_DISABLED"))
        } else {
            Issue.record("expected text content")
        }
    }
}
