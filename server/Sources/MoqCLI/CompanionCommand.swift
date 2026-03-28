import ArgumentParser
import Foundation
import MoqCompanionAI

/// CLI subcommand to start the AI companion server.
struct CompanionCommand: AsyncParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "companion",
        abstract: "Start the AI companion server for Studio integration"
    )

    @Option(name: .long, help: "Port to listen on")
    var port: Int = 8081

    @Option(name: .long, help: "Hostname to bind to")
    var hostname: String = "127.0.0.1"

    @Option(name: .long, help: "Path to a provider configuration JSON file")
    var config: String?

    mutating func run() async throws {
        let providerConfig: ProviderConfig
        if let configPath = config {
            providerConfig = try loadProviderConfig(from: configPath)
        } else {
            providerConfig = ProviderConfig.fromEnvironment()
        }

        let registry = ProviderRegistry.from(config: providerConfig)
        let redactionEngine = RedactionEngine()

        print("Starting companion server on \(hostname):\(port)...")

        let providers = await registry.listProviders()
        for provider in providers {
            let status = provider.available ? "available" : "unavailable"
            print("  Provider: \(provider.displayName) (\(provider.kind.rawValue)) — \(status)")
        }

        let app = try await buildCompanionApp(
            registry: registry,
            redactionEngine: redactionEngine,
            hostname: hostname,
            port: port
        )

        try await app.execute()
    }

    private func loadProviderConfig(from path: String) throws -> ProviderConfig {
        let expandedPath = (path as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: expandedPath)

        guard FileManager.default.fileExists(atPath: expandedPath) else {
            throw ValidationError("Provider config file not found: \(expandedPath)")
        }

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw ValidationError("Failed to read provider config at \(expandedPath): \(error.localizedDescription)")
        }

        do {
            let config = try JSONDecoder().decode(ProviderConfig.self, from: data)
            return ProviderConfig.fromEnvironment(defaults: config)
        } catch {
            throw ValidationError("Invalid provider config JSON at \(expandedPath): \(error.localizedDescription). Expected format: {\"providers\": [...]}")
        }
    }
}
