import Foundation

import Logging
import MoqCore

private let logger = Logger(label: "moqserver.parsing.SpecLoader")

/// Default implementation of spec data loading from file paths or URLs.
public struct SpecLoader: SpecLoading {
    public init() {}

    public func loadData(from source: String) throws -> Data {
        logger.info("Loading spec from \(source)")
        if source.hasPrefix("http://") || source.hasPrefix("https://") {
            logger.debug("Detected URL source")
            guard let url = URL(string: source) else {
                throw SpecLoaderError.invalidURL(source)
            }
            return try Data(contentsOf: url)
        }

        let path = (source as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: path)
        logger.debug("Detected file source: \(url.path)")

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw SpecLoaderError.fileNotFound(url.path)
        }

        return try Data(contentsOf: url)
    }
}

public enum SpecLoaderError: Error, CustomStringConvertible {
    case invalidURL(String)
    case fileNotFound(String)

    public var description: String {
        switch self {
        case .invalidURL(let url):
            return "Invalid URL: \(url)"
        case .fileNotFound(let path):
            return "File not found: \(path)"
        }
    }
}
