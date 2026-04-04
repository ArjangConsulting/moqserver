import Foundation

import Logging
import MoqCore

private let logger = Logger(label: "moqserver.parsing.HARParser")

/// Parses HAR 1.2 files into ParsedSpec for mock generation.
public struct HARParser: SpecParsing {
    public init() {}

    public func parse(data: Data) throws -> ParsedSpec {
        logger.info("Parsing HAR file (\(data.count) bytes)")
        let decoder = JSONDecoder()
        let har = try decoder.decode(HARFile.self, from: data)
        logger.debug("HAR file contains \(har.log.entries.count) entries")

        var grouped: [HARGroupKey: [CapturedExchange]] = [:]

        for entry in har.log.entries {
            guard let url = URL(string: entry.request.url) else {
                continue
            }

            let method = entry.request.method.uppercased()
            let path = normalizedPath(from: url)
            let key = HARGroupKey(method: method, path: path)
            let exchange = CapturedExchange(
                statusCode: normalizedStatusCode(entry.response.status),
                headers: responseHeaders(from: entry.response),
                body: responseBody(from: entry.response),
                requestMatch: requestMatch(from: entry.request, url: url)
            )

            if grouped[key]?.contains(exchange) != true {
                grouped[key, default: []].append(exchange)
            }
        }

        guard !grouped.isEmpty else {
            throw HARParserError.noEntries
        }

        let endpoints = grouped.keys.sorted().map { key in
            let responses = makeResponses(from: grouped[key] ?? [])
            return ParsedEndpoint(
                method: key.method,
                path: key.path,
                responses: responses,
                authRequirement: .none,
                requiredQueryParameters: [],
                requiredHeaders: [],
                requiresBody: false,
                acceptedContentTypes: []
            )
        }

        let title = har.log.creator?.name.map { "\($0) HAR Import" } ?? "HAR Import"
        let version = har.log.creator?.version ?? har.log.version

        logger.info("Parsed \(endpoints.count) endpoint(s) from HAR file grouped from \(har.log.entries.count) entries")

        return ParsedSpec(
            title: title,
            version: version,
            endpoints: endpoints
        )
    }

    private func normalizedPath(from url: URL) -> String {
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.path ?? url.path
        if path.isEmpty {
            return "/"
        }
        return path.hasPrefix("/") ? path : "/\(path)"
    }

    private func normalizedStatusCode(_ statusCode: Int) -> Int {
        if (100...599).contains(statusCode) {
            return statusCode
        }
        return 200
    }

    private func responseHeaders(from response: HARResponse) -> [(String, String)] {
        var headers = response.headers
            .filter { !$0.name.isEmpty }
            .map { ($0.name, $0.value ?? "") }

        if let mimeType = response.content.mimeType,
           !mimeType.isEmpty,
           !headers.contains(where: { $0.0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
            headers.append(("Content-Type", mimeType))
        }

        return headers
    }

    private func responseBody(from response: HARResponse) -> Data? {
        guard let text = response.content.text else {
            return nil
        }

        if response.content.encoding?.lowercased() == "base64",
           let decoded = Data(base64Encoded: text) {
            return decoded
        }

        return Data(text.utf8)
    }

    private func requestMatch(from request: HARRequest, url: URL) -> RequestMatch? {
        let query = requestQuery(from: request, url: url)
        let headers = requestHeaders(from: request)
        let bodyContains = requestBodySnippet(from: request)

        if query.isEmpty, headers.isEmpty, bodyContains == nil {
            return nil
        }

        return RequestMatch(query: query, headers: headers, bodyContains: bodyContains)
    }

    private func requestQuery(from request: HARRequest, url: URL) -> [String: String] {
        var query: [String: String] = [:]

        let items = request.queryString.isEmpty
            ? (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map {
                HARQuery(name: $0.name, value: $0.value)
            }
            : request.queryString

        for item in items where !item.name.isEmpty {
            query[item.name] = item.value ?? ""
        }

        return query
    }

    private func requestHeaders(from request: HARRequest) -> [String: String] {
        guard let mimeType = request.postData?.mimeType, !mimeType.isEmpty else {
            return [:]
        }

        return ["Content-Type": mimeType]
    }

    private func requestBodySnippet(from request: HARRequest) -> String? {
        guard let text = request.postData?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty else {
            return nil
        }

        let maxCharacters = 1024
        if text.count <= maxCharacters {
            return text
        }

        return String(text.prefix(maxCharacters))
    }

    private func makeResponses(from exchanges: [CapturedExchange]) -> [ParsedResponse] {
        var usedNames: Set<String> = []
        var didAssignDefault = false

        return exchanges.map { exchange in
            let baseName = baseVariantName(for: exchange.statusCode, allowDefault: !didAssignDefault)
            let name = uniqueVariantName(baseName: baseName, statusCode: exchange.statusCode, usedNames: usedNames)
            usedNames.insert(name)
            if name == "default" {
                didAssignDefault = true
            }

            return ParsedResponse(
                name: name,
                statusCode: exchange.statusCode,
                headers: exchange.headers,
                body: exchange.body,
                requestMatch: exchange.requestMatch
            )
        }
    }

    private func baseVariantName(for statusCode: Int, allowDefault: Bool) -> String {
        if (200...299).contains(statusCode) {
            return allowDefault ? "default" : "success-\(statusCode)"
        }
        if (300...399).contains(statusCode) {
            return "redirect-\(statusCode)"
        }
        return "error-\(statusCode)"
    }

    private func uniqueVariantName(baseName: String, statusCode: Int, usedNames: Set<String>) -> String {
        if !usedNames.contains(baseName) {
            return baseName
        }

        var suffix = 2
        var candidate = "\(baseName)-\(suffix)"
        while usedNames.contains(candidate) {
            suffix += 1
            candidate = "\(baseName)-\(suffix)"
        }
        return candidate
    }
}

private struct HARFile: Decodable {
    let log: HARLog
}

private struct HARLog: Decodable {
    let version: String
    let creator: HARCreator?
    let entries: [HAREntry]
}

private struct HARCreator: Decodable {
    let name: String?
    let version: String?
}

private struct HAREntry: Decodable {
    let request: HARRequest
    let response: HARResponse
}

private struct HARRequest: Decodable {
    let method: String
    let url: String
    let headers: [HARHeader]
    let queryString: [HARQuery]
    let postData: HARPostData?

    private enum CodingKeys: String, CodingKey {
        case method, url, headers, queryString, postData
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        method = try container.decode(String.self, forKey: .method)
        url = try container.decode(String.self, forKey: .url)
        headers = try container.decodeIfPresent([HARHeader].self, forKey: .headers) ?? []
        queryString = try container.decodeIfPresent([HARQuery].self, forKey: .queryString) ?? []
        postData = try container.decodeIfPresent(HARPostData.self, forKey: .postData)
    }
}

private struct HARResponse: Decodable {
    let status: Int
    let headers: [HARHeader]
    let content: HARContent

    private enum CodingKeys: String, CodingKey {
        case status, headers, content
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try container.decode(Int.self, forKey: .status)
        headers = try container.decodeIfPresent([HARHeader].self, forKey: .headers) ?? []
        content = try container.decodeIfPresent(HARContent.self, forKey: .content) ?? HARContent(mimeType: nil, text: nil, encoding: nil)
    }
}

private struct HARHeader: Decodable {
    let name: String
    let value: String?
}

struct HARQuery: Decodable {
    let name: String
    let value: String?
}

private struct HARPostData: Decodable {
    let mimeType: String?
    let text: String?
}

private struct HARContent: Decodable {
    let mimeType: String?
    let text: String?
    let encoding: String?

    init(mimeType: String?, text: String?, encoding: String?) {
        self.mimeType = mimeType
        self.text = text
        self.encoding = encoding
    }
}

private struct HARGroupKey: Hashable, Comparable {
    let method: String
    let path: String

    static func < (lhs: HARGroupKey, rhs: HARGroupKey) -> Bool {
        if lhs.path == rhs.path {
            return lhs.method < rhs.method
        }
        return lhs.path < rhs.path
    }
}

private struct CapturedExchange: Equatable {
    let statusCode: Int
    let headers: [(String, String)]
    let body: Data?
    let requestMatch: RequestMatch?

    static func == (lhs: CapturedExchange, rhs: CapturedExchange) -> Bool {
        lhs.statusCode == rhs.statusCode &&
        lhs.headers.elementsEqual(rhs.headers, by: ==) &&
        lhs.body == rhs.body &&
        lhs.requestMatch == rhs.requestMatch
    }
}

public enum HARParserError: Error, CustomStringConvertible {
    case noEntries

    public var description: String {
        switch self {
        case .noEntries:
            return "HAR file does not contain any importable HTTP entries."
        }
    }
}
