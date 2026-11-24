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

        var warnings: [String] = []
        let endpoints = buildParsedEndpoints(dereferenced, warnings: &warnings)
        let title = dereferenced.info.title.isEmpty ? defaultTitle : dereferenced.info.title
        let version = dereferenced.info.version.isEmpty ? defaultVersion : dereferenced.info.version

        logger.info("API parse complete: '\(title)' v\(version) — \(endpoints.count) endpoint(s)")
        return ParsedSpec(title: title, version: version, endpoints: endpoints, warnings: warnings)
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

    private static func buildParsedEndpoints(
        _ document: DereferencedDocument, warnings: inout [String]
    ) -> [ParsedEndpoint] {
        var endpoints: [ParsedEndpoint] = []
        for (path, pathItem) in document.paths {
            for endpoint in pathItem.endpoints {
                endpoints.append(
                    buildParsedEndpoint(
                        path: path.rawValue, method: endpoint.method.rawValue.uppercased(),
                        operation: endpoint.operation, pathParameters: pathItem.parameters,
                        documentSecurity: document.security, warnings: &warnings))
            }
        }
        return endpoints
    }

    private static func buildParsedEndpoint(
        path: String, method: String, operation: DereferencedOperation,
        pathParameters: [DereferencedParameter], documentSecurity: [DereferencedSecurityRequirement],
        warnings: inout [String]
    ) -> ParsedEndpoint {
        let label = "\(method) \(path)"
        let responses = buildResponses(operation, label: label, warnings: &warnings)
        let security = resolveSecurity(operation.security ?? documentSecurity, label: label)
        warnings.append(contentsOf: security.warnings)
        let requestRules = extractRequestRules(pathParameters + operation.parameters).adding(security)
        // OpenAPI 3.0 supports `requestBody` only where HTTP defines body semantics (consumers
        // must ignore it elsewhere); 3.1 permits but discourages it on GET, HEAD and DELETE. So
        // body rules are enforced only for methods that carry a body.
        let requestBody = bodyMethods.contains(method.uppercased()) ? operation.requestBody : nil

        return ParsedEndpoint(
            method: method,
            path: path,
            alias: resolveAlias(operation, method: method, path: path),
            description: operation.description?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            referenceName: operation.operationId?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            tags: operation.tags ?? [],
            responses: responses,
            authType: security.auth.type,
            authHeaderName: security.auth.headerName,
            queryParameters: requestRules.query,
            requiredQueryParameters: requestRules.requiredQueryParameters,
            requiredHeaders: requestRules.requiredHeaders,
            cookies: requestRules.cookies,
            requiresBody: requestBody?.required == true,
            acceptedContentTypes: requestBody.map { Array($0.content.keys).map(\.rawValue).sorted() } ?? []
        )
    }

    /// Methods whose request body has defined HTTP semantics (RFC 9110).
    private static let bodyMethods: Set<String> = ["POST", "PUT", "PATCH"]

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

    private static func buildResponses(
        _ operation: DereferencedOperation, label: String, warnings: inout [String]
    ) -> [ParsedResponse] {
        var responses: [ParsedResponse] = []
        var usedNames: Set<String> = []

        for outcome in operation.responseOutcomes {
            let statusString = outcome.status.rawValue
            let code = parseStatusCode(statusString)
            let baseName = variantBaseName(statusString, code)
            let description = outcome.response.description?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            let responseHeaders = extractResponseHeaders(
                outcome.response, label: "\(label) \(statusString)", warnings: &warnings)
            let content = outcome.response.content

            if content.isEmpty {
                let name = uniqueImportName(baseName, usedNames)
                usedNames.insert(name)
                responses.append(
                    ParsedResponse(name: name, statusCode: code, headers: responseHeaders, description: description))
                continue
            }

            for (index, entry) in sortContentTypes(content).enumerated() {
                let (contentType, mediaType) = entry
                let typeName = index == 0 ? baseName : "\(baseName)_\(contentTypeSuffix(contentType))"

                var headers = responseHeaders
                headers = headers.filter { $0.key.caseInsensitiveCompare(contentTypeHeader) != .orderedSame }
                headers[contentTypeHeader] = contentType

                for (exampleKey, body) in extractBodies(mediaType, contentType: contentType) {
                    let exampleName = exampleKey.map { "\(typeName)_\(defaultReferenceNameForVariantName($0))" }
                    let name = uniqueImportName(exampleName ?? typeName, usedNames)
                    usedNames.insert(name)
                    responses.append(
                        ParsedResponse(
                            name: name, statusCode: code, headers: headers, body: body, description: description))
                }
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

    /// One body per named example when a media type lists several (keyed by example name), else
    /// a single unkeyed body.
    private static func extractBodies(
        _ mediaType: DereferencedContent, contentType: String
    ) -> [(key: String?, body: String?)] {
        // OpenAPIKit fills `example` from the first of `examples`, so only `examples` decides here.
        guard let examples = mediaType.examples, examples.count > 1 else {
            return [(nil, extractBody(mediaType, contentType: contentType))]
        }
        return examples.map { key, example in
            (key, example.dataOrLegacyValue.map { formatExample($0.value, contentType: contentType) })
        }
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
        guard
            let data = try? JSONSerialization.data(
                withJSONObject: sanitizeForJSON(example), options: [.fragmentsAllowed]),
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
            return
                "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
        }
        return defaultBody(mediaType)
    }

    private static func generateJSONStub(
        _ schema: DereferencedJSONSchema, depth: Int, visited: Set<ObjectIdentifier>
    ) -> String {
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
            let itemStub =
                context.items.map { generateJSONStub($0, depth: depth + 1, visited: visited) } ?? defaultJSONBody
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
            return
                "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
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

    /// Declared response headers that carry an example. Content-Type is skipped because the
    /// content loop sets it; a header without an example is reported rather than given a made-up value.
    private static func extractResponseHeaders(
        _ response: DereferencedResponse, label: String, warnings: inout [String]
    ) -> [String: String] {
        var headers: [String: String] = [:]
        var skipped: [String] = []
        for (name, header) in response.headers ?? [:] {
            guard name.caseInsensitiveCompare(contentTypeHeader) != .orderedSame else { continue }
            if let example = headerExampleValue(header) {
                headers[name] = matchValueString(example)
            } else {
                skipped.append(name)
            }
        }
        if !skipped.isEmpty {
            let names = skipped.joined(separator: ", ")
            warnings.append("\(label): response header(s) \(names) have no example and were not imported.")
        }
        return headers
    }

    private static func headerExampleValue(_ header: DereferencedHeader) -> Any? {
        switch header.schemaOrContent {
        case .a(let context):
            if let example = context.example { return example.value }
            if let example = context.examples?.values.first?.dataOrLegacyValue { return example.value }
            return context.schema.examples.first?.value
        case .b(let content):
            guard let media = content.values.first else { return nil }
            if let example = media.example { return example.value }
            return media.examples?.values.first?.dataOrLegacyValue?.value
        }
    }

    // MARK: - Auth resolution

    private struct ResolvedAuth {
        let type: ProjectAuthConfig.AuthType
        let headerName: String?
        static let none = ResolvedAuth(type: .none, headerName: nil)
    }

    private struct ResolvedSecurity {
        var auth = ResolvedAuth.none
        var headers: [String] = []
        var cookies: [RuleMatcher] = []
        var query: [RuleMatcher] = []
        var warnings: [String] = []
    }

    /// Maps the first security requirement onto the endpoint. Every scheme in one requirement object
    /// is required together: the highest-ranked becomes `auth`, the rest become request rules.
    /// Schemes are visited in a fixed order because OpenAPIKit keeps them in an unordered dictionary.
    private static func resolveSecurity(
        _ requirements: [DereferencedSecurityRequirement], label: String
    ) -> ResolvedSecurity {
        var result = ResolvedSecurity()
        guard let first = requirements.first else { return result }
        let schemes = first.schemes.values.sorted {
            (securityRank($0.securityScheme), $0.name) < (securityRank($1.securityScheme), $1.name)
        }
        if requirements.count > 1 {
            let chosen = schemes.isEmpty ? "no auth" : schemes.map(\.name).joined(separator: " + ")
            let count = requirements.count
            result.warnings.append(
                "\(label): \(count) alternative security requirements; imported only the first (\(chosen)).")
        }
        for scoped in schemes {
            switch scoped.securityScheme.type {
            case .http(let scheme, _):
                switch scheme.lowercased() {
                case "bearer": addAuthorization(.bearer, scoped.name, to: &result, label: label)
                case "basic": addAuthorization(.basic, scoped.name, to: &result, label: label)
                default:
                    result.warnings.append(
                        "\(label): security scheme \(scoped.name) (http \(scheme)) is not supported and was skipped.")
                }
            case .oauth2, .openIdConnect:
                addAuthorization(.bearer, scoped.name, to: &result, label: label)
            case .apiKey(let name, let location):
                switch location {
                case .header:
                    if result.auth.type == .none {
                        result.auth = ResolvedAuth(type: .apiKey, headerName: name)
                    } else {
                        result.headers.append(name)
                    }
                case .cookie: result.cookies.append(RuleMatcher(name: name, required: true))
                case .query: result.query.append(RuleMatcher(name: name, required: true))
                }
            case .mutualTLS:
                result.warnings.append(
                    "\(label): security scheme \(scoped.name) (mutualTLS) is not supported and was skipped.")
            }
        }
        return result
    }

    private static func addAuthorization(
        _ type: ProjectAuthConfig.AuthType, _ schemeName: String, to result: inout ResolvedSecurity, label: String
    ) {
        switch result.auth.type {
        case .none:
            result.auth = ResolvedAuth(type: type, headerName: nil)
        case .bearer, .basic:
            result.warnings.append(
                "\(label): security scheme \(schemeName) also uses the Authorization header and was skipped.")
        case .apiKey, .header:
            result.headers.append("Authorization")
        }
    }

    /// Authorization-header schemes first, then header API keys, then cookie and query keys.
    private static func securityRank(_ scheme: OpenAPI.SecurityScheme) -> Int {
        switch scheme.type {
        case .http, .oauth2, .openIdConnect: return 0
        case .apiKey(_, .header): return 1
        case .apiKey: return 2
        case .mutualTLS: return 3
        }
    }

    // MARK: - Request rules

    private struct RequestRules {
        let query: [RuleMatcher]
        let requiredQueryParameters: [String]
        let requiredHeaders: [String]
        let cookies: [RuleMatcher]

        /// Adds the rules a security requirement implies, keeping a declared parameter's rule when
        /// both name the same header, cookie or query parameter.
        func adding(_ security: ResolvedSecurity) -> RequestRules {
            let headerNames = Set(requiredHeaders.map { $0.lowercased() })
            let extraHeaders = security.headers.filter { !headerNames.contains($0.lowercased()) }
            let cookieNames = Set(cookies.map(\.name))
            let queryNames = Set(query.map(\.name))
            let mergedQuery = (query + security.query.filter { !queryNames.contains($0.name) }).sorted {
                $0.name < $1.name
            }
            return RequestRules(
                query: mergedQuery,
                requiredQueryParameters: mergedQuery.filter { $0.required == true }.map(\.name),
                requiredHeaders: (requiredHeaders + extraHeaders).sorted(),
                cookies: (cookies + security.cookies.filter { !cookieNames.contains($0.name) }).sorted {
                    $0.name < $1.name
                })
        }
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
        return RuleMatcher(
            name: parameter.name, match: matchValue, required: true, matchType: matchValue != nil ? .equalTo : nil)
    }

    private static func matchValueString(_ value: Any) -> String {
        if let string = value as? String { return string }
        return String(describing: value)
    }

    // MARK: - Helpers

    /// A status code, or the first code of a range such as `4XX`.
    private static func parseStatusCode(_ statusString: String) -> Int {
        if let code = Int(statusString) { return code }
        if statusString.count == 3, statusString.uppercased().hasSuffix("XX"),
            let digit = statusString.first?.wholeNumberValue
        {
            return digit * 100
        }
        return defaultStatusCode
    }

    /// A short variant name tests can pass to select a variant: the status's reason phrase in
    /// camelCase (`ok`, `unauthorized`, `notFound`), the range's class for `4XX`, else `status<code>`.
    private static func variantBaseName(_ statusString: String, _ code: Int) -> String {
        if statusString == defaultVariantName { return defaultVariantName }
        if Int(statusString) == nil, let range = rangeNames[statusString.uppercased()] { return range }
        return reasonPhrases[code] ?? "status\(code)"
    }

    private static let rangeNames = [
        "1XX": "informational", "2XX": "success", "3XX": "redirection", "4XX": "clientError", "5XX": "serverError",
    ]

    private static let reasonPhrases: [Int: String] = [
        100: "continue", 101: "switchingProtocols", 102: "processing", 103: "earlyHints",
        200: "ok", 201: "created", 202: "accepted", 203: "nonAuthoritativeInformation", 204: "noContent",
        205: "resetContent", 206: "partialContent", 207: "multiStatus", 208: "alreadyReported", 226: "imUsed",
        300: "multipleChoices", 301: "movedPermanently", 302: "found", 303: "seeOther", 304: "notModified",
        307: "temporaryRedirect", 308: "permanentRedirect",
        400: "badRequest", 401: "unauthorized", 402: "paymentRequired", 403: "forbidden", 404: "notFound",
        405: "methodNotAllowed", 406: "notAcceptable", 407: "proxyAuthenticationRequired", 408: "requestTimeout",
        409: "conflict", 410: "gone", 411: "lengthRequired", 412: "preconditionFailed", 413: "contentTooLarge",
        414: "uriTooLong", 415: "unsupportedMediaType", 416: "rangeNotSatisfiable", 417: "expectationFailed",
        421: "misdirectedRequest", 422: "unprocessableContent", 423: "locked", 424: "failedDependency",
        425: "tooEarly", 426: "upgradeRequired", 428: "preconditionRequired", 429: "tooManyRequests",
        431: "requestHeaderFieldsTooLarge", 451: "unavailableForLegalReasons",
        500: "internalServerError", 501: "notImplemented", 502: "badGateway", 503: "serviceUnavailable",
        504: "gatewayTimeout", 505: "httpVersionNotSupported", 506: "variantAlsoNegotiates",
        507: "insufficientStorage", 508: "loopDetected", 511: "networkAuthenticationRequired",
    ]

    private static func uniqueImportName(_ base: String, _ used: Set<String>) -> String {
        guard used.contains(base) else { return base }
        var suffix = 2
        while used.contains("\(base)_\(suffix)") { suffix += 1 }
        return "\(base)_\(suffix)"
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
