import ArgumentParser
import MoqRuntime

public struct MoqServerCLI: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "moqserver",
        abstract: "A lightweight REST mock server powered by .moqproj project bundles",
        version: ServerInfo.versionDescription,
        subcommands: [ServeCommand.self, ValidateCommand.self]
    )

    public init() {}
}
