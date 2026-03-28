import Foundation
import MoqCore

public enum SpecInputFormat: String, Sendable {
    case auto
    case openapi
    case har
}

/// Resolves the correct parser for spec data and delegates parsing.
/// Uses the SpecParsing protocol for parser injection.
public struct ParsedSpecLoader: Sendable {
    private let openAPIParser: any SpecParsing
    private let harParser: any SpecParsing

    public init(
        openAPIParser: any SpecParsing = OpenAPIParser(),
        harParser: any SpecParsing = HARParser()
    ) {
        self.openAPIParser = openAPIParser
        self.harParser = harParser
    }

    public func parse(data: Data, source: String?, format: SpecInputFormat) throws -> ParsedSpec {
        switch format {
        case .openapi:
            return try openAPIParser.parse(data: data)
        case .har:
            return try harParser.parse(data: data)
        case .auto:
            return try parseAutomatically(data: data, source: source)
        }
    }

    private func parseAutomatically(data: Data, source: String?) throws -> ParsedSpec {
        if prefersHAR(source: source, data: data), let parsed = try? harParser.parse(data: data) {
            return parsed
        }

        if prefersOpenAPI(source: source, data: data), let parsed = try? openAPIParser.parse(data: data) {
            return parsed
        }

        if let parsed = try? openAPIParser.parse(data: data) {
            return parsed
        }

        if let parsed = try? harParser.parse(data: data) {
            return parsed
        }

        throw ParsedSpecLoaderError.unsupportedInput
    }

    private func prefersHAR(source: String?, data: Data) -> Bool {
        if let ext = sourceExtension(source), ext == "har" {
            return true
        }

        return (try? rootObject(data: data)).flatMap { root in
            guard let log = root["log"] as? [String: Any] else { return false }
            return log["entries"] is [Any]
        } ?? false
    }

    private func prefersOpenAPI(source: String?, data: Data) -> Bool {
        if let ext = sourceExtension(source), ["yaml", "yml"].contains(ext) {
            return true
        }

        return (try? rootObject(data: data))?["openapi"] != nil
    }

    private func sourceExtension(_ source: String?) -> String? {
        guard let source else { return nil }

        guard let url = URL(string: source) else {
            return (source as NSString).pathExtension.lowercased()
        }

        if url.isFileURL {
            return url.pathExtension.lowercased()
        }

        return url.pathExtension.lowercased()
    }

    private func rootObject(data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }
}

public enum ParsedSpecLoaderError: Error, CustomStringConvertible {
    case unsupportedInput

    public var description: String {
        switch self {
        case .unsupportedInput:
            return "Could not detect a supported input format. Try --format openapi or --format har."
        }
    }
}
