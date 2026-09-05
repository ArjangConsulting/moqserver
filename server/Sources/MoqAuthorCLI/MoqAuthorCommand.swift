import ArgumentParser

/// `moq-author` — scriptable, non-agent `.moqproj` authoring. Each subcommand is one atomic
/// operation: open (or create) the project, apply exactly one mutation, save, exit — there is no
/// interactive session to keep open between invocations, so a shell script or CI step composes
/// several calls the same way it would any other CLI tool.
///
/// This exists alongside `moq-mcp` (agent-driven, over MCP) and `moq-format` (Studio-driven, over
/// JSON-RPC on stdio) rather than folding into either: both of those speak a protocol meant for a
/// long-lived client, which is exactly what a one-shot script doesn't have. All three wrap the
/// same `MoqService`, so behavior (including error codes) is identical across every entry point —
/// only the framing differs.
public struct MoqAuthorCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "moq-author",
        abstract: "Script- and CI-friendly .moqproj authoring (create/edit a bundle without an MCP client)",
        subcommands: [
            ProjectCommand.self,
            EndpointCommand.self,
            VariantCommand.self,
            ImportCommand.self,
        ]
    )

    public init() {}
}

public struct ProjectCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "project",
        abstract: "Create or inspect a .moqproj bundle",
        subcommands: [ProjectCreateCommand.self, ProjectDescribeCommand.self]
    )
    public init() {}
}

public struct EndpointCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "endpoint",
        abstract: "Create, update, or remove an endpoint's metadata",
        subcommands: [EndpointUpsertCommand.self, EndpointRemoveCommand.self]
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
