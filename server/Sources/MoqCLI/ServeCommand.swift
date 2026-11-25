import ArgumentParser
import Foundation
import Logging
import MoqAddonKit
import MoqAddons
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

    @ArgumentParser.Option(
        name: .long,
        help: ArgumentHelp(
            "Log level: trace, debug, info, notice, warning, error, or critical. debug adds "
                + "per-request handling/variant-selection detail on top of the always-on "
                + "info-level access log.")
    )
    var logLevel: String = "info"

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Let the jwt-claims add-on accept unverified tokens when --hostname is not loopback. Anyone "
                + "who can reach the server can then forge a token with any claims.")
    )
    var allowUnverifiedJwt = false

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Reject mock requests that have no X-Mock-Session header (428) instead of serving them from "
                + "global state. Admin and health routes are unaffected.")
    )
    var requireSession = false

    @ArgumentParser.Option(
        name: .long,
        help: ArgumentHelp(
            "Record up to this many bytes of each mock request's body in request history "
                + "(GET /_admin/requests). 0 (default) records none. Bodies may contain credentials or "
                + "personal data; enable only for test runs.")
    )
    var captureRequestBodies: Int = 0

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Record each mock request's headers in request history (GET /_admin/requests). Credential "
                + "headers (see --redact-header) keep only their scheme, a SHA-256 "
                + "fingerprint and cookie names, never the value.")
    )
    var captureRequestHeaders = false

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Record each mock request's query parameters in request history (GET /_admin/requests). "
                + "Credential-like parameters (the same names and keywords as headers, plus --redact-header "
                + "names, or a JWT value) keep only a SHA-256 fingerprint, never the value.")
    )
    var captureRequestQuery = false

    @ArgumentParser.Option(
        name: .customLong("redact-header"),
        help: ArgumentHelp(
            "A request header name whose value is a credential, on top of the built-in list, name "
                + "keywords (auth, token, key, secret, session, ...) and JWT-looking values. Redacted in "
                + "request history and validation messages. Repeat for several.")
    )
    var redactHeaders: [String] = []

    @ArgumentParser.Option(
        name: .long,
        help: ArgumentHelp("Largest mock request body to accept, in bytes; larger bodies get 413. Default 10 MiB.")
    )
    var maxBodySize: Int = MockRouter.defaultMaxBodySize

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Answer CORS preflights and allow every origin, so browser clients can call the server. This also "
                + "lets any web page you open reach /_admin; pair it with admin credentials off loopback.")
    )
    var cors = false

    @ArgumentParser.Option(name: .long, help: "PEM certificate chain to serve HTTPS with (requires --tls-key)")
    var tlsCert: String?

    @ArgumentParser.Option(name: .long, help: "PEM private key for --tls-cert")
    var tlsKey: String?

    @ArgumentParser.Option(
        name: .long,
        help: ArgumentHelp(
            "Drop a runtime session after this many seconds without a request or admin call. 0 disables expiry.")
    )
    var sessionIdleTimeout: Int = Int(InMemoryMockStore.defaultSessionIdleTimeout)

    @ArgumentParser.Flag(
        name: .long,
        help: ArgumentHelp(
            "Reload the project when files in the bundle change. POST /_admin/reload reloads on demand either way.")
    )
    var watch = false

    public init() {}

    public mutating func validate() throws {
        guard maxBodySize > 0 else {
            throw ValidationError("--max-body-size must be a positive byte count.")
        }
        guard sessionIdleTimeout >= 0 else {
            throw ValidationError("--session-idle-timeout must be 0 (no expiry) or a positive number of seconds.")
        }
        guard (tlsCert == nil) == (tlsKey == nil) else {
            throw ValidationError("--tls-cert and --tls-key must be given together.")
        }
    }

    public mutating func run() async throws {
        // Must happen before any `Logger` is constructed anywhere in the process — Logging's
        // global factory is fixed on first use, not by this call, so every module-level `logger`
        // (this file, MockHandler, AppBootstrap, ...) picks it up lazily on its own first access.
        guard let level = Logger.Level(rawValue: logLevel.lowercased()) else {
            let validLevels = "trace, debug, info, notice, warning, error, critical"
            print("Invalid --log-level \"\(logLevel)\". Valid values: \(validLevels).")
            throw ExitCode.validationFailure
        }
        LoggingSystem.bootstrap { label in
            var handler = StreamLogHandler.standardOutput(label: label)
            handler.logLevel = level
            return handler
        }

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

        guard captureRequestBodies >= 0 else {
            print("Invalid --capture-request-bodies \(captureRequestBodies). Use 0 or a positive byte count.")
            throw ExitCode.validationFailure
        }

        warnIfExposedWithoutAdminAuth(config: serverConfig)

        await store.configureVariantOverridePersistence(path: serverConfig?.overridesPersistencePath)
        await store.configureSessionExpiry(idleTimeout: TimeInterval(sessionIdleTimeout))

        logger.info("Loading project from \(project)")
        let loadedProject = try await loadProject(from: project, into: store)
        let addons = try activateAddons(for: loadedProject)

        let endpoints = await store.allEndpoints()
        logger.info("Loaded \(endpoints.count) endpoint(s) from project")
        print("Loaded \(endpoints.count) endpoint(s) from project")
        for warning in serverConfig?.unknownOverrideWarnings(endpoints: endpoints) ?? [] {
            logger.warning("Config: \(warning)")
            print("WARNING: config \(warning)")
        }

        let tls = tlsCert.flatMap { cert in tlsKey.map { TLSFiles(certificatePath: cert, privateKeyPath: $0) } }
        let scheme = tls == nil ? "http" : "https"
        print("Starting mock server on \(scheme)://\(hostname):\(port)")
        logger.info(
            "Starting mock server", metadata: ["hostname": "\(hostname)", "port": "\(port)", "scheme": "\(scheme)"])

        let projectPath = project
        let app = try await buildApp(
            store: store, config: serverConfig, addons: addons, requireSession: requireSession,
            requestBodyCaptureLimit: captureRequestBodies, captureRequestHeaders: captureRequestHeaders,
            captureRequestQuery: captureRequestQuery, redactHeaders: redactHeaders, maxRequestBodySize: maxBodySize,
            cors: cors, tls: tls,
            reloadSource: { try Self.loadSnapshot(from: projectPath) }, hostname: hostname, port: port)

        let watcher = watch ? app.mockReloader.map { ProjectWatcher(path: projectPath, reloader: $0).start() } : nil
        defer { watcher?.cancel() }

        do {
            try await app.execute()
        } catch {
            await store.flushVariantOverridePersistence()
            try? await app.asyncShutdown()
            throw error
        }
        await store.flushVariantOverridePersistence()
        try await app.asyncShutdown()
    }

    /// Loads, validates and converts the bundle for `POST /_admin/reload` and `--watch`. Errors
    /// leave the previous version serving.
    static func loadSnapshot(from path: String) throws -> RuntimeProjectSnapshot {
        let project = try ProjectLoader().load(from: path)
        let errors = ProjectValidator().validate(project).filter { $0.severity == .error }
        guard errors.isEmpty else {
            throw ProjectSnapshotError.invalid(errors.map(\.description))
        }
        return RuntimeProjectSnapshot(
            endpoints: try ProjectToRuntimeConverter.convert(project),
            scenarios: ProjectToRuntimeConverter.scenarioOverrides(project))
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

    // MARK: - Add-ons

    private func activateAddons(for project: MoqProject) throws -> ActiveAddons {
        let environment = AddonEnvironment(hostname: hostname, allowUnverifiedJWT: allowUnverifiedJwt)
        do {
            let addons = try AddonCatalog.builtIn.activate(project.manifest.addons, environment: environment)
            if !addons.isEmpty {
                let ids = addons.addons.map { type(of: $0).id }.joined(separator: ", ")
                logger.info("Activated add-ons", metadata: ["addons": "\(ids)"])
                print("Add-ons: \(ids)")
            }
            return addons
        } catch let error as AddonActivationError {
            logger.error(
                "Add-on activation failed", metadata: ["addon": "\(error.addonID)", "error": "\(error.message)"])
            print("Aborting: \(error)")
            throw ExitCode.failure
        }
    }

    // MARK: - Project Loading

    private func loadProject(from path: String, into store: InMemoryMockStore) async throws -> MoqProject {
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
        // Defined after every endpoint is registered (overrides are checked against them), and
        // before any session exists, so each session's snapshot starts with them.
        for (name, overrides) in ProjectToRuntimeConverter.scenarioOverrides(project).sorted(by: { $0.key < $1.key }) {
            try await store.defineScenario(RuntimeScenario(name: name, overrides: overrides))
        }
        if let count = project.manifest.scenarios?.count, count > 0 {
            logger.info("Defined bundle scenarios", metadata: ["count": "\(count)"])
        }
        logger.info(
            "Loaded project",
            metadata: ["name": "\(project.manifest.name)", "path": "\(path)", "endpoints": "\(endpoints.count)"])
        print("Loaded project \"\(project.manifest.name)\" from \(path)")
        return project
    }
}

enum ProjectSnapshotError: Error, CustomStringConvertible {
    case invalid([String])

    var description: String {
        switch self {
        case .invalid(let errors):
            return "Project has \(errors.count) validation error(s): " + errors.joined(separator: "; ")
        }
    }
}
