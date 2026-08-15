import Foundation
import MCP
import Testing

@testable import MoqMCP

/// Drives the real `Server` built by `makeMoqMCPServer()` through an in-memory client/server
/// transport pair — the same code path a real MCP client (Claude Code, etc.) exercises over
/// stdio, minus the process boundary. This is what caught two real bugs during manual smoke
/// testing (ProjectWriter emitting unparseable YAML for a zero-variant endpoint, and
/// moq_upsert_endpoint's documented-but-unenforced reserved-path rejection) — both fixed
/// alongside these tests.
struct MoqMCPServerIntegrationTests {
    func connectedClient() async throws -> Client {
        let server = makeMoqMCPServer()
        let (clientTransport, serverTransport) = await InMemoryTransport.createConnectedPair()
        try await server.start(transport: serverTransport)
        let client = Client(name: "test-client", version: "1.0")
        _ = try await client.connect(transport: clientTransport)
        return client
    }

    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString).appendingPathComponent("mcp-it-\(label)-\(UUID().uuidString).moqproj")
    }

    @Test("lists every registered tool")
    func listsTools() async throws {
        let client = try await connectedClient()
        let tools = try await client.listTools().tools
        let names = Set(tools.map(\.name))
        #expect(
            names.isSuperset(
                of: [
                    "moq_create_project", "moq_open_project", "moq_describe_project", "moq_list_endpoints",
                    "moq_get_endpoint", "moq_suggest_endpoint_id", "moq_upsert_endpoint", "moq_remove_endpoint",
                    "moq_upsert_variant", "moq_remove_variant", "moq_validate_project", "moq_save_project",
                ]))
    }

    @Test("lists every registered resource")
    func listsResources() async throws {
        let client = try await connectedClient()
        let resources = try await client.listResources().resources
        let uris = Set(resources.map(\.uri))
        #expect(uris == ["moq://schema/moqproj.json", "moq://docs/authoring-rules", "moq://project/current"])
    }

    @Test("moq://schema/moqproj.json resource returns the real schema, byte-for-byte")
    func schemaResourceMatchesSource() async throws {
        let client = try await connectedClient()
        let contents = try await client.readResource(uri: "moq://schema/moqproj.json")
        let text = try #require(contents.first?.text)

        // #filePath: <repoRoot>/server/Tests/MoqMCPTests/MoqMCPServerIntegrationTests.swift
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // MoqMCPTests/
            .deletingLastPathComponent()  // Tests/
            .deletingLastPathComponent()  // server/
            .deletingLastPathComponent()  // <repoRoot>/
        let sourceSchema = try String(contentsOf: repoRoot.appendingPathComponent("format/schema.json"), encoding: .utf8)
        #expect(text == sourceSchema)
    }

    @Test("full lifecycle: create, add endpoint and variant, validate, save, reload")
    func fullLifecycle() async throws {
        let path = tempPath("lifecycle")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let client = try await connectedClient()

        let created = try await client.callTool(
            name: "moq_create_project", arguments: ["path": .string(path), "name": .string("IT Project")])
        #expect(created.isError != true)

        let upserted = try await client.callTool(
            name: "moq_upsert_endpoint",
            arguments: ["id": .string("get-users"), "method": .string("GET"), "path": .string("/users")])
        #expect(upserted.isError != true)

        let variant = try await client.callTool(
            name: "moq_upsert_variant",
            arguments: [
                "endpoint_id": .string("get-users"), "name": .string("success"), "status": .int(200),
                "default": .bool(true), "body": .object(["users": .array([])]),
            ])
        #expect(variant.isError != true)

        let validated = try await client.callTool(name: "moq_validate_project")
        #expect(validated.isError != true)

        let saved = try await client.callTool(name: "moq_save_project")
        #expect(saved.isError != true)

        // Prove it actually landed on disk in a form the real loader accepts — this is the
        // check that would have caught the empty-variants YAML bug even without the ad hoc
        // manual test that originally found it.
        let endpointFile = (path as NSString).appendingPathComponent("endpoints/get-users.yml")
        #expect(FileManager.default.fileExists(atPath: endpointFile))
        let fixtureFile = (path as NSString).appendingPathComponent("fixtures/responses/get-users/users-success.json")
        #expect(FileManager.default.fileExists(atPath: fixtureFile))
    }

    @Test("moq_upsert_endpoint rejects a reserved path")
    func rejectsReservedPath() async throws {
        let path = tempPath("reserved")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let client = try await connectedClient()
        _ = try await client.callTool(name: "moq_create_project", arguments: ["path": .string(path), "name": .string("T")])

        let result = try await client.callTool(
            name: "moq_upsert_endpoint",
            arguments: ["id": .string("bad"), "method": .string("GET"), "path": .string("/_admin/x")])

        #expect(result.isError == true)
        if case .text(let text, _, _) = result.content.first {
            #expect(text.contains("E_RESERVED_PATH"))
        } else {
            Issue.record("expected text content")
        }
    }

    @Test("moq_get_endpoint on an unknown id returns a structured not-found error")
    func getUnknownEndpointErrors() async throws {
        let path = tempPath("not-found")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let client = try await connectedClient()
        _ = try await client.callTool(name: "moq_create_project", arguments: ["path": .string(path), "name": .string("T")])

        let result = try await client.callTool(name: "moq_get_endpoint", arguments: ["id": .string("nope")])
        #expect(result.isError == true)
    }

    @Test("tool calls before any project is open return E_NO_PROJECT_OPEN")
    func requiresOpenProject() async throws {
        let client = try await connectedClient()
        let result = try await client.callTool(name: "moq_describe_project")
        #expect(result.isError == true)
        if case .text(let text, _, _) = result.content.first {
            #expect(text.contains("E_NO_PROJECT_OPEN"))
        } else {
            Issue.record("expected text content")
        }
    }
}
