import ArgumentParser
import Foundation
import Testing

@testable import MoqCLI

@Suite("ServeCommand validates --project is required")
struct ServeCommandTests {

    @Test("Validation fails when --project is not provided")
    func missingProject() {
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot([])
        }
    }

    @Test("Validation succeeds when --project is provided")
    func projectOnly() throws {
        let command = try ServeCommand.parse(["--project", "my.moqproj"])
        #expect(command.project == "my.moqproj")
    }
}

@Suite("MoqServerCLI has expected subcommands")
struct MoqServerCLITests {

    @Test("CLI has two subcommands registered")
    func subcommandCount() {
        let subcommands = MoqServerCLI.configuration.subcommands
        #expect(subcommands.count == 2)
    }

    @Test("CLI command name is moqserver")
    func commandName() {
        #expect(MoqServerCLI.configuration.commandName == "moqserver")
    }

    @Test("CLI includes serve subcommand")
    func hasServe() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == ServeCommand.self }))
    }

    @Test("CLI includes validate subcommand")
    func hasValidate() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == ValidateCommand.self }))
    }
}

@Suite("ServeCommand default values")
struct ServeCommandDefaultsTests {

    @Test("Default port is 8080")
    func defaultPort() throws {
        let command = try ServeCommand.parse(["--project", "dummy.moqproj"])
        #expect(command.port == 8080)
    }

    @Test("Default hostname is 127.0.0.1")
    func defaultHostname() throws {
        let command = try ServeCommand.parse(["--project", "dummy.moqproj"])
        #expect(command.hostname == "127.0.0.1")
    }

    @Test("Default log level is info")
    func defaultLogLevel() throws {
        let command = try ServeCommand.parse(["--project", "dummy.moqproj"])
        #expect(command.logLevel == "info")
    }

    @Test("--log-level is settable")
    func logLevelOverride() throws {
        let command = try ServeCommand.parse(["--project", "dummy.moqproj", "--log-level", "debug"])
        #expect(command.logLevel == "debug")
    }
}

@Suite("ServeCommand runtime options")
struct ServeCommandOptionTests {
    @Test("TLS files must be given together")
    func tlsPair() {
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot(["--project", "p.moqproj", "--tls-cert", "cert.pem"])
        }
        #expect(throws: Never.self) {
            _ = try ServeCommand.parseAsRoot(["--project", "p.moqproj", "--tls-cert", "c.pem", "--tls-key", "k.pem"])
        }
    }

    @Test("Body size must be positive and session timeout non-negative")
    func numericBounds() {
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot(["--project", "p.moqproj", "--max-body-size", "0"])
        }
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot(["--project", "p.moqproj", "--session-idle-timeout", "-1"])
        }
    }

    @Test("Defaults: 10 MiB bodies, 30-minute sessions, CORS and watch off")
    func defaults() throws {
        let command = try ServeCommand.parse(["--project", "p.moqproj"])
        #expect(command.maxBodySize == 10 * 1024 * 1024)
        #expect(command.sessionIdleTimeout == 1800)
        #expect(!command.cors)
        #expect(!command.watch)
    }
}

@Suite("Reload snapshot and project watcher")
struct ReloadSupportTests {
    private func makeBundle() throws -> String {
        let root = (NSTemporaryDirectory() as NSString).appendingPathComponent("reload-\(UUID().uuidString).moqproj")
        let endpoints = (root as NSString).appendingPathComponent("endpoints")
        try FileManager.default.createDirectory(atPath: endpoints, withIntermediateDirectories: true)
        try """
        version: "1"
        name: Reload
        defaults:
          delay_ms: 0
          auth:
            type: none
            verify: false
          network:
            latency_ms: 0
            jitter_ms: 0
            packet_loss_percent: 0
        scenarios:
          empty:
            variants:
              list-users: empty
        """.write(toFile: (root as NSString).appendingPathComponent("project.yml"), atomically: true, encoding: .utf8)
        try writeEndpoint(in: root, path: "/users")
        return root
    }

    private func writeEndpoint(in root: String, path: String) throws {
        try """
        id: list-users
        method: GET
        path: \(path)
        variants:
          - name: ok
            default: true
            status: 200
          - name: empty
            status: 200
        """.write(
            toFile: (root as NSString).appendingPathComponent("endpoints/list-users.yml"), atomically: true,
            encoding: .utf8)
    }

    @Test("loadSnapshot converts endpoints and scenarios keyed by state key")
    func loadsSnapshot() throws {
        let root = try makeBundle()
        defer { try? FileManager.default.removeItem(atPath: root) }
        let snapshot = try ServeCommand.loadSnapshot(from: root)
        #expect(snapshot.endpoints.map(\.stateKey) == ["GET /users"])
        #expect(snapshot.scenarios == ["empty": ["GET /users": "empty"]])
    }

    @Test("loadSnapshot refuses a bundle with validation errors")
    func rejectsInvalidBundle() throws {
        let root = try makeBundle()
        defer { try? FileManager.default.removeItem(atPath: root) }
        try writeEndpoint(in: root, path: "users-without-slash")
        #expect(throws: (any Error).self) { try ServeCommand.loadSnapshot(from: root) }
    }

    @Test("The watcher fingerprint changes when a bundle file changes")
    func fingerprintChanges() throws {
        let root = try makeBundle()
        defer { try? FileManager.default.removeItem(atPath: root) }
        let before = ProjectWatcher.fingerprint(of: root)
        try writeEndpoint(in: root, path: "/members")
        #expect(ProjectWatcher.fingerprint(of: root) != before)
        #expect(before.count == 2)
    }
}
