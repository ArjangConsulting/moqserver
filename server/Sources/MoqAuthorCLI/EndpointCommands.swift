import ArgumentParser
import MoqService

public struct EndpointUpsertCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "upsert",
        abstract: "Create or replace one endpoint's metadata from a JSON file (or - for stdin)"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Path to a JSON file matching the endpoint upsert schema (id, method, path, alias, "
                + "description, reference_name, tags, auth, request_rules, operation, network, "
                + "strict_call_count) — see moq://schema/moqproj.json or docs/FORMAT_IMPLEMENTATION.md. "
                + "Pass - to read from stdin. Variants are managed separately via `variant upsert`/`remove`.")
    )
    var json: String

    public init() {}

    public mutating func run() async throws {
        do {
            let input = try decodeJSON(EndpointUpsertInput.self, from: json)
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let document = try await service.upsertEndpoint(handle: handle, input: input, autosave: true)
            try printJSON(document)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct EndpointRemoveCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "remove",
        abstract: "Remove an endpoint and its variants"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Endpoint id to remove")
    var id: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            try await service.removeEndpoint(handle: handle, id: id, autosave: true)
            try printJSON(["removed": id])
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}
