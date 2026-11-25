import ArgumentParser
import MoqCore
import MoqService

public struct AddonListCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "list",
        abstract: "Print the bundle's enabled add-ons with their config, and the add-ons this build provides"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            try printJSON(try await service.addonState(handle: handle))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct AddonEnableCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "enable",
        abstract: "Enable an add-on in project.yml, or replace the config of one already enabled"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Add-on id, e.g. jwt-claims (see `addon list` for this build's add-ons)")
    var id: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Path to a JSON file with the add-on's config (or - for stdin). Omit for an empty config. "
                + "docs/ADDONS.md lists each add-on's keys.")
    )
    var config: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let value = try config.map { try decodeJSON(AnyCodableValue.self, from: $0) } ?? .object([:])
            let (service, handle) = try await openProjectForEditing(project, allowNetworkImport: false)
            try printJSON(try await service.enableAddon(handle: handle, id: id, config: value, autosave: true))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct AddonDisableCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "disable",
        abstract: "Remove an add-on from project.yml; refused while a variant still matches on it"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Add-on id to disable")
    var id: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openProjectForEditing(project, allowNetworkImport: false)
            try printJSON(try await service.disableAddon(handle: handle, id: id, autosave: true))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}
