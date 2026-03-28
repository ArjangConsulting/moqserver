import Foundation
import MoqCore

/// Default implementation of spec data loading from file paths or URLs.
public struct SpecLoader: SpecLoading {
    public init() {}

    public func loadData(from source: String) throws -> Data {
        if source.hasPrefix("http://") || source.hasPrefix("https://") {
            guard let url = URL(string: source) else {
                throw SpecLoaderError.invalidURL(source)
            }
            return try Data(contentsOf: url)
        }

        let path = (source as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: path)

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
