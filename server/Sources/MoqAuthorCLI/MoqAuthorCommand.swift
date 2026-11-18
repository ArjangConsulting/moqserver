import ArgumentParser

/// `moq-author` — the primary `.moqproj` authoring surface for scripts, CI and AI agents alike.
/// Each subcommand is one atomic operation: open (or create) the project, read or apply exactly
/// one mutation, save, exit — there is no session to keep open between invocations, so a caller
/// composes several calls the same way it would any other CLI tool, and can never act on a stale
/// in-memory copy of the bundle.
///
/// `moq-mcp` (over MCP) and `moq-format` (Studio, over JSON-RPC on stdio) wrap the same
/// `MoqService`, so behavior (including error codes) is identical across every entry point — only
/// the framing differs. `moq-mcp` is in maintenance: new authoring capability lands here.
public struct MoqAuthorCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "moq-author",
        abstract: "Create, inspect, edit and validate .moqproj bundles, one atomic command at a time",
        subcommands: [
            ProjectCommand.self,
            EndpointCommand.self,
            VariantCommand.self,
            ImportCommand.self,
            SchemaCommand.self,
            RulesCommand.self,
        ]
    )

    public init() {}
}

public struct ProjectCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "project",
        abstract: "Create, inspect, or validate a .moqproj bundle",
        subcommands: [ProjectCreateCommand.self, ProjectDescribeCommand.self, ProjectValidateCommand.self]
    )
    public init() {}
}

public struct EndpointCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "endpoint",
        abstract: "List, read, create, update, or remove endpoints",
        subcommands: [
            EndpointListCommand.self, EndpointGetCommand.self, EndpointSuggestIDCommand.self,
            EndpointUpsertCommand.self, EndpointRemoveCommand.self,
        ]
    )
    public init() {}
}

public struct VariantCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "variant",
        abstract: "Create, replace, or remove a response variant on an endpoint",
        subcommands: [VariantUpsertCommand.self, VariantRemoveCommand.self]
    )
    public init() {}
}

public struct ImportCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "import",
        abstract: "Import endpoints from a HAR capture or an OpenAPI spec",
        subcommands: [ImportHARCommand.self, ImportOpenAPICommand.self]
    )
    public init() {}
}
