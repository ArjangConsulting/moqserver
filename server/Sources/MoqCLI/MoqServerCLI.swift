import ArgumentParser

public struct MoqServerCLI: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "moqserver",
        abstract: "A lightweight REST mock server powered by .moqproj project bundles",
        subcommands: [ServeCommand.self, ValidateCommand.self]
    )

    public init() {}
}
