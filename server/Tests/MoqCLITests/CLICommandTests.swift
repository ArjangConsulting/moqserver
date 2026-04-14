import ArgumentParser
import Foundation
import Testing

@testable import MoqCLI

@Suite("ServeCommand validates --spec and --project mutual exclusivity")
struct ServeCommandTests {

    @Test("Validation fails when neither --spec nor --project is provided")
    func neitherSpecNorProject() {
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot([])
        }
    }

    @Test("Validation fails when both --spec and --project are provided")
    func bothSpecAndProject() {
        #expect(throws: (any Error).self) {
            _ = try ServeCommand.parseAsRoot(["--spec", "openapi.yaml", "--project", "my.moqproj"])
        }
    }

    @Test("Validation succeeds when only --spec is provided")
    func specOnly() throws {
        let command = try ServeCommand.parse(["--spec", "openapi.yaml"])
        #expect(command.spec == "openapi.yaml")
        #expect(command.project == nil)
    }

    @Test("Validation succeeds when only --project is provided")
    func projectOnly() throws {
        let command = try ServeCommand.parse(["--project", "my.moqproj"])
        #expect(command.project == "my.moqproj")
        #expect(command.spec == nil)
    }
}

@Suite("MoqServerCLI has expected subcommands")
struct MoqServerCLITests {

    @Test("CLI has four subcommands registered")
    func subcommandCount() {
        let subcommands = MoqServerCLI.configuration.subcommands
        #expect(subcommands.count == 4)
    }

    @Test("CLI command name is moqserver")
    func commandName() {
        #expect(MoqServerCLI.configuration.commandName == "moqserver")
    }

    @Test("CLI includes serve subcommand")
    func hasServe() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == ServeCommand.self }))
    }

    @Test("CLI includes init subcommand")
    func hasInit() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == InitCommand.self }))
    }

    @Test("CLI includes validate subcommand")
    func hasValidate() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == ValidateCommand.self }))
    }

    @Test("CLI includes validate-spec subcommand")
    func hasValidateSpec() {
        #expect(MoqServerCLI.configuration.subcommands.contains(where: { $0 == ValidateSpecCommand.self }))
    }
}

@Suite("ServeCommand default values")
struct ServeCommandDefaultsTests {

    @Test("Default port is 8080")
    func defaultPort() throws {
        let command = try ServeCommand.parse(["--spec", "dummy.yaml"])
        #expect(command.port == 8080)
    }

    @Test("Default hostname is 127.0.0.1")
    func defaultHostname() throws {
        let command = try ServeCommand.parse(["--spec", "dummy.yaml"])
        #expect(command.hostname == "127.0.0.1")
    }

    @Test("Default format is auto")
    func defaultFormat() throws {
        let command = try ServeCommand.parse(["--spec", "dummy.yaml"])
        #expect(command.format == "auto")
    }
}

@Suite("InitCommand default values")
struct InitCommandDefaultsTests {

    @Test("Default format is auto")
    func defaultFormat() throws {
        let command = try InitCommand.parse(["--spec", "dummy.yaml"])
        #expect(command.format == "auto")
    }

    @Test("Default output is ./mocks")
    func defaultOutput() throws {
        let command = try InitCommand.parse(["--spec", "dummy.yaml"])
        #expect(command.output == "./mocks")
    }
}
