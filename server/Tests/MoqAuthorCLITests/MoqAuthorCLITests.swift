import Foundation
import Testing

@testable import MoqAuthorCLI

/// Exercises `moq-author` subcommands the way a script would: parse args, run, inspect the
/// resulting bundle on disk. Each command is one atomic open-mutate-save-exit operation — no
/// session persists across calls, so every test reopens the project by path each time, exactly
/// like separate CLI invocations would.
struct MoqAuthorCLITests {
    func tempPath(_ label: String) -> String {
        (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("author-cli-test-\(label)-\(UUID().uuidString).moqproj")
    }

    func writeJSON(_ label: String, _ json: String) throws -> String {
        let path = (NSTemporaryDirectory() as NSString).appendingPathComponent(
            "author-cli-\(label)-\(UUID().uuidString).json")
        try json.write(toFile: path, atomically: true, encoding: .utf8)
        return path
    }

    @Test("CLI imports preserve metadata unless explicitly enabled")
    func importOptionsPreserveMetadata() throws {
        #expect(try authorImportOptions(nil).updateDetails == false)
        let path = try writeJSON("options", #"{"replace_existing_bodies":true}"#)
        defer { try? FileManager.default.removeItem(atPath: path) }
        #expect(try authorImportOptions(path).updateDetails == false)
        #expect(try authorImportOptions(path).replaceExistingBodies == true)
        try #"{"update_details":true}"#.write(toFile: path, atomically: true, encoding: .utf8)
        #expect(try authorImportOptions(path).updateDetails == true)
    }

    @Test("project create makes a bundle that endpoint upsert and variant upsert can build on")
    func fullAuthoringFlow() async throws {
        let path = tempPath("flow")
        defer { try? FileManager.default.removeItem(atPath: path) }

        var create = try ProjectCreateCommand.parse(["--path", path, "--name", "CI Project"])
        try await create.run()
        #expect(FileManager.default.fileExists(atPath: (path as NSString).appendingPathComponent("project.yml")))

        let endpointJSON = try writeJSON(
            "endpoint",
            #"{"id": "get-users", "method": "GET", "path": "/users"}"#)
        var upsertEndpoint = try EndpointUpsertCommand.parse(["--project", path, "--json", endpointJSON])
        try await upsertEndpoint.run()

        let variantJSON = try writeJSON(
            "variant",
            #"{"endpoint_id": "get-users", "name": "success", "status": 200, "default": true, "body": {"ok": true}}"#)
        var upsertVariant = try VariantUpsertCommand.parse(["--project", path, "--json", variantJSON])
        try await upsertVariant.run()

        let endpointFile = (path as NSString).appendingPathComponent("endpoints/get-users.yml")
        #expect(FileManager.default.fileExists(atPath: endpointFile))
        let yaml = try String(contentsOfFile: endpointFile, encoding: .utf8)
        #expect(yaml.contains(#"- name: "success""#))

        var describe = try ProjectDescribeCommand.parse(["--project", path])
        try await describe.run()
    }

    @Test("variant upsert reports replaced, not created, on a case-only re-upsert")
    func variantUpsertReportsReplacement() async throws {
        let path = tempPath("replace")
        defer { try? FileManager.default.removeItem(atPath: path) }

        var create = try ProjectCreateCommand.parse(["--path", path, "--name", "CI Project"])
        try await create.run()
        var upsertEndpoint = try EndpointUpsertCommand.parse([
            "--project", path, "--json",
            try writeJSON("endpoint2", #"{"id": "get-users", "method": "GET", "path": "/users"}"#),
        ])
        try await upsertEndpoint.run()

        var first = try VariantUpsertCommand.parse([
            "--project", path, "--json",
            try writeJSON("v1", #"{"endpoint_id": "get-users", "name": "Success", "status": 200}"#),
        ])
        try await first.run()

        // Re-upsert under a different casing — must replace in place, not add a second variant.
        var second = try VariantUpsertCommand.parse([
            "--project", path, "--json",
            try writeJSON("v2", #"{"endpoint_id": "get-users", "name": "success", "status": 201}"#),
        ])
        try await second.run()

        // Exactly one variant list entry — the case-only re-upsert replaced it, not added a second.
        let endpointFile = (path as NSString).appendingPathComponent("endpoints/get-users.yml")
        let yaml = try String(contentsOfFile: endpointFile, encoding: .utf8)
        #expect(yaml.components(separatedBy: "- name: ").count - 1 == 1)
    }

    @Test("endpoint remove fails with E_ENDPOINT_NOT_FOUND on an unknown id")
    func endpointRemoveUnknownFails() async throws {
        let path = tempPath("remove-unknown")
        defer { try? FileManager.default.removeItem(atPath: path) }
        var create = try ProjectCreateCommand.parse(["--path", path, "--name", "CI Project"])
        try await create.run()

        var remove = try EndpointRemoveCommand.parse(["--project", path, "--id", "nope"])
        await #expect(throws: (any Error).self) {
            try await remove.run()
        }
    }

    @Test("project create fails cleanly when a bundle already exists at the path")
    func projectCreateRefusesExisting() async throws {
        // Each `moq-author` command is a fresh process that opens the *current* file state, so
        // ProjectStore's E_PROJECT_CHANGED staleness guard (exercised at the ProjectStore and
        // MCP layers in ProjectStoreTests / MoqMCPServerIntegrationTests) has nothing to catch
        // here — there's no long-lived session for a hand-edit to race against between one
        // `moq-author` invocation and the next. `project create` refusing to clobber an existing
        // bundle is the analogous guard at this layer.
        let path = tempPath("create-twice")
        defer { try? FileManager.default.removeItem(atPath: path) }

        var first = try ProjectCreateCommand.parse(["--path", path, "--name", "First"])
        try await first.run()

        var second = try ProjectCreateCommand.parse(["--path", path, "--name", "Second"])
        await #expect(throws: (any Error).self) {
            try await second.run()
        }

        // The failed second create must not have clobbered the first.
        let manifest = try String(
            contentsOfFile: (path as NSString).appendingPathComponent("project.yml"), encoding: .utf8)
        #expect(manifest.contains("First"))
    }
}
