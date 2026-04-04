import Foundation
import MoqCore

/// Loads mock response files from a directory structure.
///
/// Convention:
/// ```
/// mocks/pets/GET.json              → GET /pets default
/// mocks/pets/GET.error-500.json    → GET /pets variant "error-500"
/// mocks/pets/{petId}/GET.json      → GET /pets/{petId} default
/// mocks/pets/GET.error-500.meta.json → Optional metadata (statusCode, headers, delay, requestMatch)
/// ```
public struct MockFileLoader: MockFileLoading {
    private struct VariantMeta: Decodable {
        var statusCode: Int?
        var headers: [String: String]?
        var delay: TimeInterval?
        var requestMatch: RequestMatchMeta?
    }

    private struct RequestMatchMeta: Decodable {
        var query: [String: String]?
        var headers: [String: String]?
        var bodyContains: String?
    }

    public init() {}

    public func load(from directory: String) throws -> [Endpoint] {
        let expandedPath = (directory as NSString).expandingTildeInPath
        let baseURL = URL(fileURLWithPath: expandedPath, isDirectory: true).resolvingSymlinksInPath()
        let fm = FileManager.default

        guard fm.fileExists(atPath: baseURL.path) else {
            throw MockFileLoaderError.directoryNotFound(baseURL.path)
        }

        guard let enumerator = fm.enumerator(
            at: baseURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var grouped: [EndpointKey: [ResponseVariant]] = [:]

        for case let fileURL as URL in enumerator {
            guard fileURL.pathExtension == "json" else { continue }
            guard !fileURL.lastPathComponent.hasSuffix(".meta.json") else { continue }

            let resolvedFileURL = fileURL.resolvingSymlinksInPath()

            let relativePath = resolvedFileURL.path
                .replacingOccurrences(of: baseURL.path, with: "")
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

            guard let (method, apiPath, variantName) = Self.parseFilePath(relativePath) else {
                continue
            }

            let data = try Data(contentsOf: resolvedFileURL)
            let key = EndpointKey(method: HTTPMethodValue(rawValue: method), path: apiPath)
            let metadata = try Self.loadMeta(for: resolvedFileURL)
            let statusCode = metadata?.statusCode.map { HTTPStatusCode(code: UInt($0)) }
                ?? Self.statusCodeFromVariantName(variantName)
            var headers = metadata?.headers?.map { ($0.key, $0.value) } ?? []
            if !headers.contains(where: { $0.0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
                headers.append(("Content-Type", "application/json"))
            }
            let requestMatch = metadata?.requestMatch.map {
                RequestMatch(
                    query: $0.query ?? [:],
                    headers: $0.headers ?? [:],
                    bodyContains: $0.bodyContains
                )
            }

            let variant = ResponseVariant(
                name: variantName,
                statusCode: statusCode,
                headers: headers,
                body: data,
                delay: metadata?.delay,
                requestMatch: requestMatch
            )

            grouped[key, default: []].append(variant)
        }

        return grouped.map { key, variants in
            Endpoint(
                key: key,
                authRequirement: .none,
                variants: variants
            )
        }
    }

    static func parseFilePath(_ path: String) -> (method: String, apiPath: String, variantName: String)? {
        let components = path.split(separator: "/").map(String.init)
        guard !components.isEmpty else { return nil }

        let filename = components.last!
        let dirComponents = components.dropLast()

        guard filename.hasSuffix(".json") else { return nil }
        let nameWithoutExt = String(filename.dropLast(".json".count))
        let parts = nameWithoutExt.split(separator: ".", maxSplits: 1).map(String.init)

        let method = parts[0].uppercased()
        let validMethods = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT"]
        guard validMethods.contains(method) else { return nil }

        let variantName = parts.count > 1 ? parts[1] : "default"
        let apiPath = "/" + dirComponents.joined(separator: "/")

        return (method, apiPath, variantName)
    }

    private static func statusCodeFromVariantName(_ name: String) -> HTTPStatusCode {
        if name == "default" { return .ok }
        if name.hasPrefix("error-"), let code = Int(name.dropFirst("error-".count)) {
            return HTTPStatusCode(code: UInt(code))
        }
        return .ok
    }

    private static func loadMeta(for bodyFileURL: URL) throws -> VariantMeta? {
        let metaURL = bodyFileURL.deletingPathExtension().appendingPathExtension("meta.json")
        guard FileManager.default.fileExists(atPath: metaURL.path) else {
            return nil
        }

        let data = try Data(contentsOf: metaURL)
        return try JSONDecoder().decode(VariantMeta.self, from: data)
    }
}

public enum MockFileLoaderError: Error, CustomStringConvertible {
    case directoryNotFound(String)

    public var description: String {
        switch self {
        case .directoryNotFound(let path):
            return "Mocks directory not found: \(path)"
        }
    }
}
