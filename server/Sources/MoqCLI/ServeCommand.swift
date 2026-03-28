import ArgumentParser
import Foundation
import MoqCore
import MoqFormat
import MoqParsing
import MoqRuntime
import Vapor

/// `moqserver serve` — loads an OpenAPI spec, HAR file, or .moqproj directory and starts the mock server.
public struct ServeCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "serve",
        abstract: "Start the mock server from an OpenAPI spec, HAR file, or .moqproj project"
    )

    @ArgumentParser.Option(name: .long, help: "Path or URL to an OpenAPI spec or HAR file")
    var spec: String?

    @ArgumentParser.Option(name: .long, help: "Path to a .moqproj project directory")
    var project: String?

    @ArgumentParser.Option(name: .long, help: "Input format: auto, openapi, or har (only used with --spec)")
    var format: String = "auto"

    @ArgumentParser.Option(name: .long, help: "Port to listen on")
    var port: Int = 8080

    @ArgumentParser.Option(name: .long, help: "Hostname to bind to")
    var hostname: String = "127.0.0.1"

    @ArgumentParser.Option(name: .long, help: "Path to a YAML or JSON config file")
    var config: String?

    @ArgumentParser.Option(name: .long, help: "Path to the mocks directory (only used with --spec)")
    var mocks: String?

    public init() {}

    public mutating func validate() throws {
        guard spec != nil || project != nil else {
            throw ValidationError("Either --spec or --project must be provided")
        }
        guard spec == nil || project == nil else {
            throw ValidationError("--spec and --project are mutually exclusive")
        }
    }

    public mutating func run() async throws {
        let store = InMemoryMockStore()
        let configLoader = ConfigLoader()

        // Load config if provided
        var serverConfig: ServerConfig?
        if let configPath = config {
            serverConfig = try configLoader.load(from: configPath)
            print("Loaded config from \(configPath)")
        }

        await store.configureVariantOverridePersistence(path: serverConfig?.overridesPersistencePath)

        if let projectPath = project {
            try await loadProject(from: projectPath, into: store)
        } else if let specPath = spec {
            try await loadSpec(from: specPath, into: store, serverConfig: serverConfig)
        }

        let endpointCount = await store.allEndpoints().count
        let source = project != nil ? "project" : (format == "auto" ? "input" : format)
        print("Loaded \(endpointCount) endpoint(s) from \(source)")
        print("Starting mock server on \(hostname):\(port)")

        let app = try await buildApp(store: store, config: serverConfig, hostname: hostname, port: port)

        defer {
            Task {
                try? await app.asyncShutdown()
            }
        }

        try await app.execute()
    }

    // MARK: - Project Loading

    private func loadProject(from path: String, into store: InMemoryMockStore) async throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: path)
        let endpoints = try ProjectToRuntimeConverter.convert(project)
        for endpoint in endpoints {
            await store.register(endpoint)
        }
        print("Loaded project \"\(project.manifest.name)\" from \(path)")
    }

    // MARK: - Spec Loading

    private func loadSpec(
        from specPath: String,
        into store: InMemoryMockStore,
        serverConfig: ServerConfig?
    ) async throws {
        let specLoader = SpecLoader()
        let mockFileLoader = MockFileLoader()

        // Load and parse the spec
        let specData = try specLoader.loadData(from: specPath)
        let inputFormat = resolveFormat(format)
        let parsedSpecLoader = ParsedSpecLoader()
        let parsedSpec = try parsedSpecLoader.parse(data: specData, source: specPath, format: inputFormat)

        // Validate spec and print warnings
        let validator = OpenAPISpecValidator()
        let diagnostics = validator.validate(data: specData)
        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }
        if !errors.isEmpty || !warnings.isEmpty {
            print("Spec validation: \(errors.count) error(s), \(warnings.count) warning(s)")
            for diagnostic in diagnostics {
                print("  \(diagnostic)")
            }
        }

        if !errors.isEmpty {
            throw CleanExit.message("Aborting: spec has \(errors.count) validation error(s). Fix them or use --spec with a valid spec.")
        }

        // Convert and register endpoints
        let endpoints = EndpointConverter.makeEndpoints(from: parsedSpec)
        for endpoint in endpoints {
            await store.register(endpoint)
        }

        // Load mock files if provided (overrides spec variants)
        let mocksDir = mocks ?? serverConfig?.mocksDirectory
        if let mocksDir {
            let mockEndpoints = try mockFileLoader.load(from: mocksDir)
            for mockEndpoint in mockEndpoints {
                await store.mergeVariants(from: mockEndpoint)
            }
            print("Loaded mock files from \(mocksDir)")
        }
    }

    private func resolveFormat(_ format: String) -> SpecInputFormat {
        switch format.lowercased() {
        case "openapi": return .openapi
        case "har": return .har
        default: return .auto
        }
    }
}
