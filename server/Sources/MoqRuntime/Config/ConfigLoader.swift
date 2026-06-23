import Foundation
import Logging
import Yams

private let logger = Logger(label: "moqserver.runtime.ConfigLoader")

/// Loads server configuration from YAML or JSON files.
public struct ConfigLoader: Sendable {
    public init() {}

    public func load(from path: String) throws -> ServerConfig {
        logger.info("Loading config from \(path)")
        let expandedPath = (path as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: expandedPath)

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ConfigLoaderError.fileNotFound(url.path)
        }

        let data = try Data(contentsOf: url)

        // Pick the decoder from the file extension so a malformed YAML file
        // reports a YAML error instead of a confusing JSON fallback error.
        if url.pathExtension.lowercased() == "json" {
            do {
                let config = try JSONDecoder().decode(ServerConfig.self, from: data)
                logger.debug("Config loaded as JSON")
                return config
            } catch {
                throw ConfigLoaderError.invalidConfig(url.path, "JSON: \(error)")
            }
        }

        let yamlError: Error
        do {
            let config = try YAMLDecoder().decode(ServerConfig.self, from: data)
            logger.debug("Config loaded as YAML")
            return config
        } catch {
            yamlError = error
        }

        // Unknown extensions still get a JSON fallback, but a failure reports both attempts.
        do {
            let config = try JSONDecoder().decode(ServerConfig.self, from: data)
            logger.debug("Config loaded as JSON after YAML decode failed")
            return config
        } catch {
            throw ConfigLoaderError.invalidConfig(url.path, "YAML: \(yamlError); JSON: \(error)")
        }
    }
}

public enum ConfigLoaderError: Error, CustomStringConvertible {
    case fileNotFound(String)
    case invalidConfig(String, String)

    public var description: String {
        switch self {
        case .fileNotFound(let path):
            return "Config file not found: \(path)"
        case .invalidConfig(let path, let detail):
            return "Could not decode config file \(path): \(detail)"
        }
    }
}
