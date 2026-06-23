import ArgumentParser
import Foundation
import Logging
import MoqCore
import MoqFormat
import MoqRuntime
import Vapor

private let logger = Logger(label: "moqserver.cli.ServeCommand")

/// `moqserver serve` — loads a .moqproj directory and starts the mock server.
public struct ServeCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "serve",
        abstract: "Start the mock server from a .moqproj project"
    )

    @ArgumentParser.Option(name: .long, help: "Path to a .moqproj project directory")
    var project: String

    @ArgumentParser.Option(name: .long, help: "Port to listen on")
    var port: Int = 8080

    @ArgumentParser.Option(name: .long, help: "Hostname to bind to")
    var hostname: String = "127.0.0.1"

    @ArgumentParser.Option(name: .long, help: "Path to a YAML or JSON config file")
    var config: String?

    public init() {}

    public mutating func run() async throws {
        let store = InMemoryMockStore()
        let configLoader = ConfigLoader()

        var serverConfig: ServerConfig?
        if let configPath = config {
            serverConfig = try configLoader.load(from: configPath)
            logger.info("Loaded config from \(configPath)")
            print("Loaded config from \(configPath)")
        }

        let configErrors = serverConfig?.validationErrors() ?? []
        if !configErrors.isEmpty {
            for error in configErrors {
                logger.error("Invalid config: \(error)")
                print("Invalid config: \(error)")
            }
            throw ExitCode.failure
        }

        warnIfExposedWithoutAdminAuth(config: serverConfig)

        await store.configureVariantOverridePersistence(path: serverConfig?.overridesPersistencePath)

        logger.info("Loading project from \(project)")
        try await loadProject(from: project, into: store)

        let endpointCount = await store.allEndpoints().count
        logger.info("Loaded \(endpointCount) endpoint(s) from project")
        print("Loaded \(endpointCount) endpoint(s) from project")
        print("Starting mock server on \(hostname):\(port)")
        logger.info("Starting mock server", metadata: ["hostname": "\(hostname)", "port": "\(port)"])

        let app = try await buildApp(store: store, config: serverConfig, hostname: hostname, port: port)

        do {
            try await app.execute()
        } catch {
            try? await app.asyncShutdown()
            throw error
        }
        try await app.asyncShutdown()
    }

    /// Warns when the server is reachable from other machines but the admin API has no credentials.
    private func warnIfExposedWithoutAdminAuth(config: ServerConfig?) {
        let loopbackHostnames: Set<String> = ["127.0.0.1", "localhost", "::1"]
        guard !loopbackHostnames.contains(hostname), config?.admin == nil else { return }
        let warning = """
            WARNING: Binding to \(hostname) without admin credentials. Anyone who can reach this host \
            can list endpoints and change active variants via /_admin. Add an `admin` section with a \
            bearerToken or apiKey to the config file to require authentication.
            """
        logger.warning("\(warning)")
        print(warning)
    }

    // MARK: - Project Loading

    private func loadProject(from path: String, into store: InMemoryMockStore) async throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: path)

        // Validate the project before serving — fail fast on schema or semantic errors.
        let validator = ProjectValidator()
        let diagnostics = validator.validate(project)
        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }
        if !diagnostics.isEmpty {
            print("Project validation: \(errors.count) error(s), \(warnings.count) warning(s)")
            for diagnostic in diagnostics {
                print("  \(diagnostic)")
            }
        }
        if !errors.isEmpty {
            logger.error("Project validation failed", metadata: ["errors": "\(errors.count)"])
            print(
                "Aborting: project has \(errors.count) validation error(s). Run `moqserver validate --project \(path)` for details."
            )
            throw ExitCode.failure
        }

        let endpoints = try ProjectToRuntimeConverter.convert(project)
        for endpoint in endpoints {
            await store.register(endpoint)
        }
        logger.info(
            "Loaded project",
            metadata: ["name": "\(project.manifest.name)", "path": "\(path)", "endpoints": "\(endpoints.count)"])
        print("Loaded project \"\(project.manifest.name)\" from \(path)")
    }
}
