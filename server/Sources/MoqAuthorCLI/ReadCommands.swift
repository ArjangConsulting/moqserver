import ArgumentParser
import MoqService

// Read-only commands. None of them saves, so running one never changes a bundle on disk.

public struct ProjectValidateCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "validate",
        abstract: "Validate a bundle and print every diagnostic as JSON; exits 1 when it has errors"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Flag(name: .long, help: "Also exit 1 when the bundle has warnings (e.g. W_NO_DEFAULT_VARIANT)")
    var strict = false

    public init() {}

    public mutating func run() async throws {
        let result: ValidationResult
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            result = try await service.validateProject(handle: handle)
            try printJSON(result)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
        // The diagnostics are already on stdout; the exit status is what a script branches on.
        if result.errorCount > 0 || (strict && result.warningCount > 0) {
            throw ExitCode.failure
        }
    }
}

public struct EndpointListCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "list",
        abstract: "List a bundle's endpoints (id, method, path, variant count, default variant) as JSON"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Only endpoints whose path contains this text")
    var path: String?

    @Option(name: .long, help: "Only endpoints with this HTTP method")
    var method: String?

    @Option(name: .long, help: "Only endpoints carrying this tag")
    var tag: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let filter = EndpointFilter(filterPath: path, filterMethod: method, filterTag: tag)
            let summaries = try await service.listEndpoints(handle: handle, filter: filter)
            try printJSON(EndpointListResult(endpoints: summaries))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct EndpointGetCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "get",
        abstract: "Print one endpoint with all of its variants as JSON"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Endpoint id to print")
    var id: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let document = try await service.getEndpoint(handle: handle, id: id)
            try printJSON(document)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct EndpointSuggestIDCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "suggest-id",
        abstract: "Print the id, alias and reference name the importers would give a method and path"
    )

    @Option(name: .long, help: "HTTP method, e.g. GET")
    var method: String

    @Option(name: .long, help: "Request path, e.g. /users/{id}")
    var path: String

    @Option(name: .long, help: "Alias to derive the names from instead of the path")
    var alias: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let service = MoqService(allowNetworkImport: false)
            try printJSON(service.suggestEndpointID(SuggestEndpointIDInput(method: method, path: path, alias: alias)))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct SchemaCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "schema",
        abstract: "Print the JSON Schema for .moqproj documents"
    )

    public init() {}

    public mutating func run() async throws {
        print(AuthoringReference.schemaJSON, terminator: "")
    }
}

public struct RulesCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "rules",
        abstract: "Print the authoring rules the JSON Schema cannot express, as Markdown"
    )

    public init() {}

    public mutating func run() async throws {
        print(AuthoringReference.rulesMarkdown())
    }
}
