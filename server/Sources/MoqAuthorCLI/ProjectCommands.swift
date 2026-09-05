import ArgumentParser
import MoqCore
import MoqService

public struct ProjectCreateCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "create",
        abstract: "Create a new empty .moqproj bundle"
    )

    @Option(name: .long, help: "Directory to create the bundle at")
    var path: String

    @Option(name: .long, help: "Project name")
    var name: String

    @Option(name: .long, help: "Project description")
    var description: String?

    public init() {}

    public mutating func run() async throws {
        do {
            let service = MoqService(allowNetworkImport: allowNetworkImportFromEnvironment)
            let handle = await service.openSession()
            let manifest = ProjectManifest(
                name: name, description: description,
                defaults: ProjectDefaults(
                    auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
            // `force` on the underlying API discards *this session's own* unsaved changes before
            // create/open — meaningless for a fresh, one-shot CLI process, which never has any.
            // It does not let create overwrite an existing bundle at `path`; that's always
            // E_PROJECT_ALREADY_EXISTS, by design, regardless of force.
            let result = try await service.createProject(handle: handle, manifest: manifest, path: path, force: false)
            try printJSON(result)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct ProjectDescribeCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "describe",
        abstract: "Print a project's name, description, and endpoint count as JSON"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let result = try await service.describeProject(handle: handle)
            try printJSON(result)
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}
