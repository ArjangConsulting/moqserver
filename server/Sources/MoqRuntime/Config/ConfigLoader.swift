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

        let decoder = YAMLDecoder()
        do {
            let config = try decoder.decode(ServerConfig.self, from: data)
            logger.debug("Config loaded as YAML")
            return config
        } catch {
            logger.debug("YAML decode failed, trying JSON fallback")
            return try JSONDecoder().decode(ServerConfig.self, from: data)
        }
    }
}

public enum ConfigLoaderError: Error, CustomStringConvertible {
    case fileNotFound(String)

    public var description: String {
        switch self {
        case .fileNotFound(let path):
            return "Config file not found: \(path)"
        }
    }
}
