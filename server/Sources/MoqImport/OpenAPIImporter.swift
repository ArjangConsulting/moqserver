import Foundation
import Logging
import MoqCore
import OpenAPIKit
import Yams

private let logger = Logger(label: "moqserver.import.OpenAPIImporter")

/// Parses OpenAPI 3.x specs (YAML/JSON) into a `ParsedSpec` using OpenAPIKit. Ports the
/// structure of Kotlin's `OpenAPIImportParser` (which uses `swagger-parser` and additionally
/// supports Swagger 2.0) to OpenAPIKit's enum-based schema model.
///
/// **Swagger 2.0 is intentionally unsupported** — OpenAPIKit has no 2.0 conversion path
/// equivalent to swagger-parser's `SwaggerConverter`. A document with a `swagger: "2.0"` marker
/// is rejected with `OpenAPIImportError.unsupportedSpecVersion` rather than silently
/// misparsed.
public enum OpenAPIImporter {
    private static let maxStubDepth = 32
    private static let defaultTitle = "Untitled API"
    private static let defaultVersion = "1.0"
    private static let defaultStatusCode = 200
    private static let defaultVariantName = "default"
    private static let contentTypeHeader = "Content-Type"
    private static let applicationJSON = "application/json"
    private static let defaultJSONBody = "{}"

    public static func parse(_ content: String) throws -> ParsedSpec {
        logger.info("Parsing API spec (\(content.utf8.count) bytes)")
        let document = try decodeDocument(content)
        let dereferenced: DereferencedDocument
        do {
            dereferenced = try document.locallyDereferenced()
        } catch {
            throw OpenAPIImportError.invalidSpec("Unable to resolve references in API spec: \(error)")
        }

        let endpoints = buildParsedEndpoints(dereferenced)
        let title = dereferenced.info.title.isEmpty ? defaultTitle : dereferenced.info.title
        let version = dereferenced.info.version.isEmpty ? defaultVersion : dereferenced.info.version

        logger.info("API parse complete: '\(title)' v\(version) — \(endpoints.count) endpoint(s)")
        return ParsedSpec(title: title, version: version, endpoints: endpoints)
    }

    // MARK: - Decoding

    private static func decodeDocument(_ content: String) throws -> OpenAPI.Document {
        let v31Error: Error
        do {
            return try YAMLDecoder().decode(OpenAPI.Document.self, from: content)
        } catch {
            v31Error = error
        }
        if let document = OpenAPI30Fallback.decodeAndUpconvert(content) {
            return document
        }
        if looksLikeSwagger2(content) {
            throw OpenAPIImportError.unsupportedSpecVersion(
                "Swagger 2.0 specs are not supported. Convert to OpenAPI 3.x first (e.g. with the swagger2openapi tool) and re-import."
            )
        }
        let v30Error = OpenAPI30Fallback.decodeError(content)
        throw OpenAPIImportError.invalidSpec(
            "Unable to parse API spec as OpenAPI 3.1 (\(v31Error)) or OpenAPI 3.0 (\(v30Error)).")
    }

    private static func looksLikeSwagger2(_ content: String) -> Bool {
        if content.range(of: #""swagger"\s*:\s*"2\.0""#, options: .regularExpression) != nil {
            return true
        }
        let multilineRegex = try? NSRegularExpression(
            pattern: #"^swagger\s*:\s*["']?2\.0["']?"#, options: [.anchorsMatchLines])
        let range = NSRange(content.startIndex..<content.endIndex, in: content)
        return multilineRegex?.firstMatch(in: content, range: range) != nil
    }

    // MARK: - Endpoint building

    private static func buildParsedEndpoints(_ document: DereferencedDocument) -> [ParsedEndpoint] {
        var endpoints: [ParsedEndpoint] = []
        for (path, pathItem) in document.paths {
            for endpoint in pathItem.endpoints {
                endpoints.append(
                    buildParsedEndpoint(
                        path: path.rawValue, method: endpoint.method.rawValue.uppercased(),
                        operation: endpoint.operation, pathParameters: pathItem.parameters,
                        documentSecurity: document.security))
            }
        }
        return endpoints
    }

    private static func buildParsedEndpoint(
        path: String, method: String, operation: DereferencedOperation,
        pathParameters: [DereferencedParameter], documentSecurity: [DereferencedSecurityRequirement]
    ) -> ParsedEndpoint {
        let responses = buildResponses(operation)
        let auth = resolveAuth(operation.security ?? documentSecurity)
        let requestRules = extractRequestRules(pathParameters + operation.parameters)

        return ParsedEndpoint(
            method: method,
            path: path,
            alias: resolveAlias(operation, method: method, path: path),
            description: operation.description?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            referenceName: operation.operationId?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            tags: operation.tags ?? [],
            responses: responses,
            authType: auth.type,
            authHeaderName: auth.headerName,
            queryParameters: requestRules.query,
            requiredQueryParameters: requestRules.requiredQueryParameters,
            requiredHeaders: requestRules.requiredHeaders,
            cookies: requestRules.cookies
        )
    }

    private static func resolveAlias(_ operation: DereferencedOperation, method: String, path: String) -> String {
        if let summary = operation.summary?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty {
            return summary
        }
        if let operationID = operation.operationId?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty {
            return EndpointAlias.humanize(operationID)
        }
        return EndpointAlias.defaultAlias(method: method, path: path)
    }

    // MARK: - Response building

    private static func buildResponses(_ operation: DereferencedOperation) -> [ParsedResponse] {
        var responses: [ParsedResponse] = []
        var usedNames: Set<String> = []

        for outcome in operation.responseOutcomes {
            let statusString = outcome.status.rawValue
            let code = parseStatusCode(statusString)
            let baseName =
                outcome.response.description?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
                ?? statusCodeToVariantName(statusString, code)
            let responseHeaders = extractResponseHeaders(outcome.response)
            let content = outcome.response.content

            if content.isEmpty {
                let name = uniqueImportName(baseName, usedNames)
                usedNames.insert(name)
                responses.append(ParsedResponse(name: name, statusCode: code, headers: responseHeaders))
                continue
            }

            for (index, entry) in sortContentTypes(content).enumerated() {
                let (contentType, mediaType) = entry
                let suffix = index == 0 ? "" : "-\(contentTypeSuffix(contentType))"
                let name = uniqueImportName(baseName + suffix, usedNames)
                usedNames.insert(name)

                var headers = responseHeaders
                headers = headers.filter { $0.key.caseInsensitiveCompare(contentTypeHeader) != .orderedSame }
                headers[contentTypeHeader] = contentType

                responses.append(
                    ParsedResponse(name: name, statusCode: code, headers: headers, body: extractBody(mediaType, contentType: contentType)))
            }
        }

        if responses.isEmpty {
            responses.append(
                ParsedResponse(
                    name: defaultVariantName, statusCode: defaultStatusCode,
                    headers: [contentTypeHeader: applicationJSON], body: defaultJSONBody))
        }
        return responses
    }

    private static func sortContentTypes(_ content: DereferencedContent.Map) -> [(String, DereferencedContent)] {
        var sorted: [(String, DereferencedContent)] = []
        var jsonEntry: (String, DereferencedContent)?
        var rest: [(String, DereferencedContent)] = []
        for (contentType, value) in content {
            if contentType.rawValue == applicationJSON {
                jsonEntry = (contentType.rawValue, value)
            } else {
                rest.append((contentType.rawValue, value))
            }
        }
        if let jsonEntry { sorted.append(jsonEntry) }
        sorted.append(contentsOf: rest.sorted { $0.0 < $1.0 })
        return sorted
    }

    private static func extractBody(_ mediaType: DereferencedContent, contentType: String) -> String? {
        if let example = mediaType.example {
            return formatExample(example.value, contentType: contentType)
        }
        if let example = mediaType.examples?.values.first, let codable = example.dataOrLegacyValue {
            return formatExample(codable.value, contentType: contentType)
        }
        if let schema = mediaType.schema {
            return generateStubFromSchema(schema, mediaType: contentType)
        }
        return defaultBody(contentType)
    }

    private static func formatExample(_ example: Any, contentType: String) -> String {
        guard isJSONMediaType(contentType) else { return String(describing: example) }
        if let string = example as? String { return "\"\(string)\"" }
        guard let data = try? JSONSerialization.data(withJSONObject: sanitizeForJSON(example), options: [.fragmentsAllowed]),
            let text = String(data: data, encoding: .utf8)
        else {
            return String(describing: example)
        }
        return text
    }

    /// Converts an `AnyCodable`-decoded value tree into types `JSONSerialization` accepts.
    private static func sanitizeForJSON(_ value: Any) -> Any {
        switch value {
        case is NSNull: return NSNull()
        case let dict as [String: Any]: return dict.mapValues(sanitizeForJSON)
        case let array as [Any]: return array.map(sanitizeForJSON)
        case let string as String: return string
        case let bool as Bool: return bool
        case let number as any Numeric: return number
        default: return String(describing: value)
        }
    }

    private static func generateStubFromSchema(_ schema: DereferencedJSONSchema, mediaType: String) -> String? {
        if isJSONMediaType(mediaType) {
            return generateJSONStub(schema, depth: 0, visited: [])
        }
        if isXMLMediaType(mediaType) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>"
        }
        if isHTMLMediaType(mediaType) {
            return "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
        }
        return defaultBody(mediaType)
    }

    private static func generateJSONStub(_ schema: DereferencedJSONSchema, depth: Int, visited: Set<ObjectIdentifier>) -> String {
        if depth >= maxStubDepth { return defaultJSONBody }
        if let example = schema.examples.first {
            return formatExample(example.value, contentType: applicationJSON)
        }

        switch schema {
        case .string:
            return "\"string\""
        case .integer:
            return "0"
        case .number:
            return "0.0"
        case .boolean:
            return "false"
        case .array(_, let context):
            let itemStub = context.items.map { generateJSONStub($0, depth: depth + 1, visited: visited) } ?? defaultJSONBody
            return "[\(itemStub)]"
        case .object(_, let context):
            let properties = context.properties
            guard !properties.isEmpty else { return defaultJSONBody }
            let entries = properties.sorted { $0.key < $1.key }.map { key, value in
                "\"\(key)\":\(generateJSONStub(value, depth: depth + 1, visited: visited))"
            }
            return "{\(entries.joined(separator: ","))}"
        default:
            // Composite schemas (allOf/oneOf/anyOf/not) and typeless fragments don't map onto a
            // single stub shape; fall back to an empty object like the Kotlin importer does.
            return defaultJSONBody
        }
    }

    private static func defaultBody(_ mediaType: String) -> String? {
        let lower = mediaType.lowercased()
        if isJSONMediaType(lower) { return defaultJSONBody }
        if isXMLMediaType(lower) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>" }
        if isHTMLMediaType(lower) {
            return "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
        }
        if lower == "text/csv" { return "column1,column2\nvalue1,value2" }
        if lower.hasPrefix("text/") { return "mock-response" }
        if lower.hasPrefix("image/svg") {
            return
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" fill=\"#ccc\"/><text x=\"10\" y=\"55\" font-size=\"12\">mock</text></svg>"
        }
        return nil
    }

    // MARK: - Response headers

    private static func extractResponseHeaders(_ response: DereferencedResponse) -> [String: String] {
        // Response header *examples* (as opposed to Content-Type, set by the caller) are a minor
        // documentation nicety in practice and are intentionally not extracted here — headers
        // are populated from the content-type loop only.
        [:]
    }

    // MARK: - Auth resolution

    private struct ResolvedAuth {
        let type: ProjectAuthConfig.AuthType
        let headerName: String?
        static let none = ResolvedAuth(type: .none, headerName: nil)
    }

    private static func resolveAuth(_ requirements: [DereferencedSecurityRequirement]) -> ResolvedAuth {
        for requirement in requirements {
            for (_, scoped) in requirement.schemes {
                if let resolved = resolveAuth(scoped.securityScheme) { return resolved }
            }
        }
        return .none
    }

    private static func resolveAuth(_ scheme: OpenAPI.SecurityScheme) -> ResolvedAuth? {
        switch scheme.type {
        case .http(let httpScheme, _):
            switch httpScheme.lowercased() {
            case "bearer": return ResolvedAuth(type: .bearer, headerName: nil)
            case "basic": return ResolvedAuth(type: .basic, headerName: nil)
            default: return nil
            }
        case .apiKey(let name, _):
            return ResolvedAuth(type: .apiKey, headerName: name)
        case .mutualTLS:
            return nil
        case .oauth2, .openIdConnect:
            return ResolvedAuth(type: .bearer, headerName: nil)
        }
    }

    // MARK: - Request rules

    private struct RequestRules {
        let query: [RuleMatcher]
        let requiredQueryParameters: [String]
        let requiredHeaders: [String]
        let cookies: [RuleMatcher]
    }

    private static func extractRequestRules(_ parameters: [DereferencedParameter]) -> RequestRules {
        var query: [String: RuleMatcher] = [:]
        var queryOrder: [String] = []
        var requiredHeaders: [String] = []
        var cookies: [RuleMatcher] = []

        for parameter in parameters {
            switch parameter.location {
            case .query:
                guard let rule = parseQueryRule(parameter) else { continue }
                if let existing = query[parameter.name] {
                    query[parameter.name] = mergeQueryRules(existing, rule)
                } else {
                    query[parameter.name] = rule
                    queryOrder.append(parameter.name)
                }
            case .header:
                if parameter.required { requiredHeaders.append(parameter.name) }
            case .cookie:
                if parameter.required { cookies.append(parseCookieRule(parameter)) }
            case .path, .querystring:
                break
            }
        }

        let sortedQuery = queryOrder.compactMap { query[$0] }.sorted { $0.name < $1.name }
        return RequestRules(
            query: sortedQuery,
            requiredQueryParameters: sortedQuery.filter { $0.required == true }.map(\.name),
            requiredHeaders: requiredHeaders.sorted(),
            cookies: cookies.sorted { $0.name < $1.name }
        )
    }

    private static func parameterExampleValue(_ parameter: DereferencedParameter) -> Any? {
        guard case .a(let schemaContext) = parameter.schemaOrContent else { return nil }
        if let example = schemaContext.example { return example.value }
        if let example = schemaContext.schema.examples.first { return example.value }
        if let defaultValue = schemaContext.schema.defaultValue { return defaultValue.value }
        return nil
    }

    private static func parseQueryRule(_ parameter: DereferencedParameter) -> RuleMatcher? {
        let isRequired = parameter.required
        let matchValue = parameterExampleValue(parameter).map(matchValueString)
        guard isRequired || matchValue != nil else { return nil }
        return RuleMatcher(
            name: parameter.name, match: matchValue, required: isRequired ? true : nil,
            matchType: matchValue != nil ? .equalTo : nil)
    }

    private static func mergeQueryRules(_ existing: RuleMatcher, _ incoming: RuleMatcher) -> RuleMatcher {
        let matchValue = existing.match ?? incoming.match
        return RuleMatcher(
            name: incoming.name, match: matchValue,
            required: (existing.required == true || incoming.required == true) ? true : nil,
            matchType: matchValue != nil ? .equalTo : nil)
    }

    private static func parseCookieRule(_ parameter: DereferencedParameter) -> RuleMatcher {
        let matchValue = parameterExampleValue(parameter).map(matchValueString)
        return RuleMatcher(name: parameter.name, match: matchValue, required: true, matchType: matchValue != nil ? .equalTo : nil)
    }

    private static func matchValueString(_ value: Any) -> String {
        if let string = value as? String { return string }
        return String(describing: value)
    }

    // MARK: - Helpers

    private static func parseStatusCode(_ statusString: String) -> Int {
        Int(statusString) ?? defaultStatusCode
    }

    private static func statusCodeToVariantName(_ statusString: String, _ code: Int) -> String {
        if statusString == defaultVariantName { return defaultVariantName }
        switch code {
        case 200...299: return defaultVariantName
        case 400...599: return "error-\(code)"
        default: return "\(code)"
        }
    }

    private static func uniqueImportName(_ base: String, _ used: Set<String>) -> String {
        guard used.contains(base) else { return base }
        var suffix = 2
        while used.contains("\(base)-\(suffix)") { suffix += 1 }
        return "\(base)-\(suffix)"
    }

    private static func contentTypeSuffix(_ contentType: String) -> String {
        let lower = contentType.lowercased()
        if lower.contains("xml") { return "xml" }
        if lower.contains("html") { return "html" }
        if lower.contains("plain") { return "text" }
        if lower.contains("csv") { return "csv" }
        if lower.contains("pdf") { return "pdf" }
        if lower.contains("png") { return "png" }
        if lower.contains("svg") { return "svg" }
        if lower.contains("jpeg") || lower.contains("jpg") { return "jpeg" }
        if lower.contains("gif") { return "gif" }
        if lower.contains("octet-stream") { return "binary" }
        if lower.contains("form-urlencoded") { return "form" }
        return lower.split(separator: "/").last.map(String.init) ?? lower
    }

    private static func isJSONMediaType(_ type: String) -> Bool {
        let lower = type.lowercased()
        return lower == "application/json" || lower.hasSuffix("+json") || lower.contains("/json")
    }

    private static func isXMLMediaType(_ type: String) -> Bool {
        let lower = type.lowercased()
        if lower.hasPrefix("image/svg") { return false }
        return lower == "application/xml" || lower == "text/xml" || lower.hasSuffix("+xml")
    }

    private static func isHTMLMediaType(_ type: String) -> Bool {
        let lower = type.lowercased()
        return lower == "text/html" || lower == "application/xhtml+xml"
    }
}

public enum OpenAPIImportError: Error, CustomStringConvertible, Equatable {
    case invalidSpec(String)
    case unsupportedSpecVersion(String)

    public var description: String {
        switch self {
        case .invalidSpec(let message): return message
        case .unsupportedSpecVersion(let message): return message
        }
    }
}

extension String {
    fileprivate var nonEmpty: String? { isEmpty ? nil : self }
}
