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
}
