import ArgumentParser
import MoqService

public struct ImportHARCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "har",
        abstract: "Import endpoints from a HAR capture"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Path to the .har file to import")
    var harPath: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Path to a JSON file of import options (accept_paths, update_details, "
                + "replace_existing_bodies) — omit for defaults (import everything, don't touch existing "
                + "endpoints' details or bodies). Filter the capture down to what you actually want mocked "
                + "before importing; a raw device/browser capture is full of unrelated traffic.")
    )
    var options: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let common = try authorImportOptions(options)
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let summary = try await service.importHAR(
                handle: handle, input: ImportHARInput(path: harPath), common: common, autosave: true)
            try printJSON(summary)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct ImportOpenAPICommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "openapi",
        abstract: "Import endpoints from an OpenAPI 3.x spec"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Local file path to the spec, or an http(s):// URL (URL sources require "
                + "MOQ_AUTHOR_ALLOW_NETWORK=1 on this process)")
    )
    var source: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Path to a JSON file of auth for a URL source (bearer, or basic {username,password}, or "
                + "header {name,value}) — irrelevant for a local file source")
    )
    var authJson: String?

    @Option(name: .long, help: "Path to a JSON file of import options (accept_paths, update_details, ...)")
    var options: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let auth = try authJson.map { try decodeJSON(ImportAuthInput.self, from: $0) }
            let common = try authorImportOptions(options)
            let (service, handle) = try await openExistingProject(
                project, allowNetworkImport: allowNetworkImportFromEnvironment)
            let summary = try await service.importOpenAPI(
                handle: handle, input: ImportOpenAPIInput(source: source, auth: auth), common: common, autosave: true)
            try printJSON(summary)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}
