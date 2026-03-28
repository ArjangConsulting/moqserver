import Foundation
import Yams

/// Loads server configuration from YAML or JSON files.
public struct ConfigLoader: Sendable {
    public init() {}

    public func load(from path: String) throws -> ServerConfig {
        let expandedPath = (path as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: expandedPath)

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ConfigLoaderError.fileNotFound(url.path)
        }

        let data = try Data(contentsOf: url)

        let decoder = YAMLDecoder()
        do {
            return try decoder.decode(ServerConfig.self, from: data)
        } catch {
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
