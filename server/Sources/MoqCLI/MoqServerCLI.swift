import ArgumentParser

public struct MoqServerCLI: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "moqserver",
        abstract: "A lightweight REST mock server powered by OpenAPI specs",
        subcommands: [ServeCommand.self, InitCommand.self, ValidateCommand.self, ValidateSpecCommand.self, CompanionCommand.self]
    )

    public init() {}
}
