import Foundation
import MoqCore
import OpenAPIKit
import OpenAPIKit30
import OpenAPIKitCompat
import Yams

/// Parses OpenAPI 3.0.x and 3.1.x specs into ParsedSpec.
public struct OpenAPIParser: SpecParsing {
    public init() {}

    public func parse(data: Data) throws -> ParsedSpec {
        let document = try parseDocument(data: data)
        let securitySchemes = extractSecuritySchemes(from: document)
        let parameterComponents = extractParameters(from: document)
        let requestBodyComponents = extractRequestBodies(from: document)
        let responseComponents = extractResponses(from: document)
        var endpoints: [ParsedEndpoint] = []

        for (path, pathItemEither) in document.paths {
            guard let pathItem = pathItemEither.pathItemValue else { continue }
            let pathString = path.rawValue

            for endpoint in pathItem.endpoints {
                let responses = buildResponses(
                    from: endpoint.operation,
                    responseComponents: responseComponents
                )
                let auth = resolveAuth(
                    operation: endpoint.operation,
                    globalSecurity: document.security,
                    securitySchemes: securitySchemes
                )
                let requestRules = extractRequestRules(
                    operation: endpoint.operation,
                    pathParameters: pathItem.parameters,
                    parameterComponents: parameterComponents,
                    requestBodyComponents: requestBodyComponents
                )

                endpoints.append(ParsedEndpoint(
                    method: endpoint.method.rawValue.uppercased(),
                    path: pathString,
                    responses: responses,
                    authRequirement: auth,
                    requiredQueryParameters: requestRules.requiredQueryParameters,
                    requiredHeaders: requestRules.requiredHeaders,
                    requiresBody: requestRules.requiresBody,
                    acceptedContentTypes: requestRules.acceptedContentTypes
                ))
            }
        }

        return ParsedSpec(
            title: document.info.title,
            version: document.info.version,
            endpoints: endpoints
        )
    }

    private func parseDocument(data: Data) throws -> OpenAPIKit.OpenAPI.Document {
        if let doc = try? JSONDecoder().decode(OpenAPIKit.OpenAPI.Document.self, from: data) {
            return doc
        }
        if let doc = try? YAMLDecoder().decode(OpenAPIKit.OpenAPI.Document.self, from: data) {
            return doc
        }

        if let doc30 = try? JSONDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: data) {
            return doc30.convert(to: .v3_1_0)
        }
        if let doc30 = try? YAMLDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: data) {
            return doc30.convert(to: .v3_1_0)
        }

        throw OpenAPIParserError.unsupportedFormat
    }

    private func buildResponses(
        from operation: OpenAPIKit.OpenAPI.Operation,
        responseComponents: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Response]
    ) -> [ParsedResponse] {
        var responses: [ParsedResponse] = []
        var usedNames: Set<String> = []

        for (statusCode, responseEither) in operation.responses {
            guard let response = resolveResponse(responseEither, from: responseComponents) else { continue }

            let code: Int
            switch statusCode.value {
            case .default:
                code = 200
            case .status(let statusInt):
                code = statusInt
            case .range(let range):
                switch range {
                case .information: code = 100
                case .success: code = 200
                case .redirect: code = 300
                case .clientError: code = 400
                case .serverError: code = 500
                }
            }

            let baseVariantName = statusCodeToVariantName(statusCode)
            let responseHeaders = extractResponseHeaders(from: response)

            // Extract all content type payloads for content negotiation
            let payloads = extractAllPayloads(from: response)

            if payloads.isEmpty {
                // No content — single variant with no body
                let variantName = uniqueVariantName(
                    baseName: baseVariantName,
                    statusCode: statusCode,
                    usedNames: usedNames
                )
                usedNames.insert(variantName)
                responses.append(ParsedResponse(
                    name: variantName,
                    statusCode: code,
                    headers: responseHeaders,
                    body: nil
                ))
            } else {
                // Create a variant per content type for content negotiation
                for (index, payload) in payloads.enumerated() {
                    let suffix = index == 0 ? "" : "-\(contentTypeSuffix(payload.contentType))"
                    let variantName = uniqueVariantName(
                        baseName: baseVariantName + suffix,
                        statusCode: statusCode,
                        usedNames: usedNames
                    )
                    usedNames.insert(variantName)

                    var headers = responseHeaders.filter {
                        $0.0.caseInsensitiveCompare("Content-Type") != .orderedSame
                    }
                    headers.append(("Content-Type", payload.contentType))

                    responses.append(ParsedResponse(
                        name: variantName,
                        statusCode: code,
                        headers: headers,
                        body: payload.body
                    ))
                }
            }
        }

        if responses.isEmpty {
            responses.append(ParsedResponse(
                name: "default",
                statusCode: 200,
                headers: [("Content-Type", "application/json")],
                body: Data("{}".utf8)
            ))
        }

        return responses
    }

    /// Returns a short suffix for a content type (e.g., "xml", "html", "text").
    private func contentTypeSuffix(_ contentType: String) -> String {
        let lower = contentType.lowercased()
        if lower.contains("xml") { return "xml" }
        if lower.contains("html") { return "html" }
        if lower.contains("plain") { return "text" }
        if lower.contains("csv") { return "csv" }
        if lower.contains("pdf") { return "pdf" }
        if lower.contains("png") { return "png" }
        if lower.contains("jpeg") || lower.contains("jpg") { return "jpeg" }
        if lower.contains("gif") { return "gif" }
        if lower.contains("svg") { return "svg" }
        if lower.contains("octet-stream") { return "binary" }
        if lower.contains("form-urlencoded") { return "form" }
        // Fall back to subtype
        let parts = lower.split(separator: "/", maxSplits: 1)
        return parts.count == 2 ? String(parts[1]) : lower
    }

    private func uniqueVariantName(
        baseName: String,
        statusCode: OpenAPIKit.OpenAPI.Response.StatusCode,
        usedNames: Set<String>
    ) -> String {
        if !usedNames.contains(baseName) {
            return baseName
        }

        var candidate: String
        switch statusCode.value {
        case .status(let code):
            if baseName == "default" {
                candidate = "success-\(code)"
            } else {
                candidate = "\(baseName)-\(code)"
            }
        case .range(let range):
            candidate = "\(baseName)-\(range.rawValue)"
        case .default:
            candidate = "\(baseName)-fallback"
        }

        if !usedNames.contains(candidate) {
            return candidate
        }

        var suffix = 2
        var uniqued = "\(candidate)-\(suffix)"
        while usedNames.contains(uniqued) {
            suffix += 1
            uniqued = "\(candidate)-\(suffix)"
        }
        return uniqued
    }

    private func resolveResponse(
        _ responseEither: Either<OpenAPIKit.OpenAPI.Reference<OpenAPIKit.OpenAPI.Response>, OpenAPIKit.OpenAPI.Response>,
        from components: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Response]
    ) -> OpenAPIKit.OpenAPI.Response? {
        if let response = responseEither.responseValue {
            return response
        }

        guard case .a(let reference) = responseEither else { return nil }
        guard let name = reference.name,
              let componentKey = OpenAPIKit.OpenAPI.ComponentKey(rawValue: name),
              let responseEither = components[componentKey] else {
            return nil
        }
        return responseEither
    }

    /// Extracts payloads for all content types in the response, JSON first.
    private func extractAllPayloads(
        from response: OpenAPIKit.OpenAPI.Response
    ) -> [(contentType: String, body: Data?)] {
        let contentMap = response.content
        guard !contentMap.isEmpty else { return [] }

        // Sort: JSON first, then other types alphabetically for determinism
        var sorted: [(contentType: String, content: OpenAPIKit.OpenAPI.Content)] = []
        if let json = contentMap[.json]?.contentValue {
            sorted.append((OpenAPIKit.OpenAPI.ContentType.json.rawValue, json))
        }
        for (contentType, contentEither) in contentMap.sorted(by: { $0.key.rawValue < $1.key.rawValue }) {
            guard let content = contentEither.contentValue else { continue }
            let typeString = contentType.rawValue
            if typeString == OpenAPIKit.OpenAPI.ContentType.json.rawValue { continue }
            sorted.append((typeString, content))
        }

        return sorted.map { (contentType, content) in
            let body: Data?
            if let example = content.example?.value {
                body = encodeExample(example, for: contentType)
            } else if let schema = content.schema {
                body = generateStubFromSchema(schema, mediaType: contentType)
            } else {
                body = defaultBody(for: contentType)
            }
            return (contentType, body)
        }
    }

    private func extractResponseHeaders(from response: OpenAPIKit.OpenAPI.Response) -> [(String, String)] {
        guard let responseHeaders = response.headers else { return [] }

        var headers: [(String, String)] = []
        for (name, headerEither) in responseHeaders {
            guard let header = headerEither.headerValue else { continue }
            if let value = headerValue(from: header) {
                headers.append((name, value))
            }
        }
        return headers
    }

    private func headerValue(from header: OpenAPIKit.OpenAPI.Header) -> String? {
        switch header.schemaOrContent {
        case .a(let schemaContext):
            if let example = schemaContext.example?.value {
                return stringify(example)
            }
            if let schema = schemaContext.schema.schemaValue,
               let sample = generateStubFromSchema(schema, mediaType: "text/plain"),
               let value = String(data: sample, encoding: .utf8) {
                return value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            }
            return nil
        case .b(let contentMap):
            for (_, contentEither) in contentMap {
                guard let content = contentEither.contentValue else { continue }
                if let example = content.example?.value {
                    return stringify(example)
                }
                if let schema = content.schema,
                   let sample = generateStubFromSchema(schema, mediaType: "text/plain"),
                   let value = String(data: sample, encoding: .utf8) {
                    return value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                }
            }
            return nil
        }
    }

    private func encodeExample(_ example: Any, for mediaType: String) -> Data {
        if isJSONMediaType(mediaType) {
            if JSONSerialization.isValidJSONObject(example),
               let jsonData = try? JSONSerialization.data(withJSONObject: example, options: [.sortedKeys]) {
                return jsonData
            }

            if let string = example as? String {
                if let data = try? JSONSerialization.data(withJSONObject: string, options: []) {
                    return data
                }
            }
        }

        if isXMLMediaType(mediaType) {
            if let dict = example as? [String: Any] {
                return Data(encodeExampleAsXML(dict, rootElement: "root").utf8)
            }
            if let array = example as? [Any] {
                return Data(encodeExampleAsXML(["items": array], rootElement: "root").utf8)
            }
        }

        if let string = example as? String {
            return Data(string.utf8)
        }

        if let number = example as? NSNumber {
            return Data(number.stringValue.utf8)
        }

        if let bool = example as? Bool {
            return Data((bool ? "true" : "false").utf8)
        }

        if let data = try? JSONSerialization.data(withJSONObject: ["value": stringify(example)], options: []),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: String],
           let wrapped = object["value"] {
            return Data(wrapped.utf8)
        }

        return defaultBody(for: mediaType)
    }

    private func encodeExampleAsXML(_ dict: [String: Any], rootElement: String) -> String {
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<\(rootElement)>"
        for (key, value) in dict.sorted(by: { $0.key < $1.key }) {
            xml += encodeValueAsXML(value, name: key)
        }
        xml += "</\(rootElement)>"
        return xml
    }

    private func encodeValueAsXML(_ value: Any, name: String) -> String {
        if let dict = value as? [String: Any] {
            let children = dict.sorted(by: { $0.key < $1.key }).map {
                encodeValueAsXML($0.value, name: $0.key)
            }.joined()
            return "<\(name)>\(children)</\(name)>"
        }
        if let array = value as? [Any] {
            return array.map { encodeValueAsXML($0, name: "item") }.joined()
        }
        return "<\(name)>\(xmlEscape(stringify(value)))</\(name)>"
    }

    private func xmlEscape(_ string: String) -> String {
        string
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    private func defaultBody(for mediaType: String) -> Data {
        let lower = mediaType.lowercased()
        if isJSONMediaType(lower) {
            return Data("{}".utf8)
        }
        if isXMLMediaType(lower) {
            return Data("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>".utf8)
        }
        if isHTMLMediaType(lower) {
            return Data("<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>".utf8)
        }
        if lower == "text/csv" {
            return Data("column1,column2\nvalue1,value2".utf8)
        }
        if lower.hasPrefix("text/") {
            return Data("mock-response".utf8)
        }
        if lower == "application/pdf" {
            // Minimal valid PDF placeholder
            return Data("%PDF-1.0\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R>>endobj\nxref\n0 4\ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n0\n%%EOF".utf8)
        }
        if lower.hasPrefix("image/svg") {
            return Data("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" fill=\"#ccc\"/><text x=\"10\" y=\"55\" font-size=\"12\">mock</text></svg>".utf8)
        }
        if lower.hasPrefix("image/") {
            // 1x1 transparent PNG
            let png: [UInt8] = [
                0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
                0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, // IDAT
                0x54, 0x78, 0x9C, 0x62, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x01, 0xE5, 0x27, 0xDE, 0xFC, 0x00, 0x00,
                0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, // IEND
                0x60, 0x82
            ]
            return Data(png)
        }
        // Generic binary/unknown
        return Data()
    }

    private func generateStubFromSchema(_ schema: OpenAPIKit.JSONSchema, mediaType: String) -> Data? {
        if isJSONMediaType(mediaType) {
            return generateJSONStubFromSchema(schema)
        }

        // Check SVG before XML (SVG has +xml suffix but needs special handling)
        if isSVGMediaType(mediaType) {
            return defaultBody(for: mediaType)
        }

        if isXMLMediaType(mediaType) {
            return generateXMLStubFromSchema(schema, rootElement: "root")
        }

        if isHTMLMediaType(mediaType) {
            return generateHTMLStub(from: schema)
        }

        if isTextMediaType(mediaType) {
            // For plain text, generate a simple string representation
            let jsonStub = generateJSONStubFromSchema(schema)
            guard let jsonStub,
                  let text = String(data: jsonStub, encoding: .utf8) else {
                return Data("mock-response".utf8)
            }
            return Data(text.trimmingCharacters(in: CharacterSet(charactersIn: "\"")).utf8)
        }

        // For binary/unknown types, fall back to default body
        return defaultBody(for: mediaType)
    }

    private func generateJSONStubFromSchema(_ schema: OpenAPIKit.JSONSchema) -> Data? {
        switch schema.value {
        case .string:
            return Data("\"string\"".utf8)
        case .integer:
            return Data("0".utf8)
        case .number:
            return Data("0.0".utf8)
        case .boolean:
            return Data("false".utf8)
        case .array(_, let arrayContext):
            if let items = arrayContext.items,
               let itemStub = generateJSONStubFromSchema(items),
               let item = String(data: itemStub, encoding: .utf8) {
                return Data("[\(item)]".utf8)
            }
            return Data("[]".utf8)
        case .object(_, let objectContext):
            var dict: [String: Any] = [:]
            for (propName, propSchema) in objectContext.properties {
                if let propStub = generateJSONStubFromSchema(propSchema),
                   let value = try? JSONSerialization.jsonObject(with: propStub) {
                    dict[propName] = value
                }
            }
            if JSONSerialization.isValidJSONObject(dict),
               let data = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]) {
                return data
            }
            return Data("{}".utf8)
        default:
            return Data("{}".utf8)
        }
    }

    // MARK: - XML Stub Generation

    private func generateXMLStubFromSchema(_ schema: OpenAPIKit.JSONSchema, rootElement: String) -> Data? {
        let xml = generateXMLElement(schema, name: rootElement)
        let declaration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        return Data((declaration + xml).utf8)
    }

    private func generateXMLElement(_ schema: OpenAPIKit.JSONSchema, name: String) -> String {
        switch schema.value {
        case .string:
            return "<\(name)>string</\(name)>"
        case .integer:
            return "<\(name)>0</\(name)>"
        case .number:
            return "<\(name)>0.0</\(name)>"
        case .boolean:
            return "<\(name)>false</\(name)>"
        case .array(_, let arrayContext):
            if let items = arrayContext.items {
                let itemXML = generateXMLElement(items, name: "item")
                return "<\(name)>\(itemXML)</\(name)>"
            }
            return "<\(name)/>"
        case .object(_, let objectContext):
            let children = objectContext.properties.map { (propName, propSchema) in
                generateXMLElement(propSchema, name: propName)
            }.joined()
            return "<\(name)>\(children)</\(name)>"
        default:
            return "<\(name)/>"
        }
    }

    // MARK: - HTML Stub Generation

    private func generateHTMLStub(from schema: OpenAPIKit.JSONSchema) -> Data {
        var body = "<p>mock-response</p>"
        if case .object(_, let objectContext) = schema.value {
            let rows = objectContext.properties.map { (name, propSchema) in
                let value: String
                switch propSchema.value {
                case .string: value = "string"
                case .integer: value = "0"
                case .number: value = "0.0"
                case .boolean: value = "false"
                default: value = ""
                }
                return "<tr><td>\(name)</td><td>\(value)</td></tr>"
            }.joined()
            body = "<table><thead><tr><th>Field</th><th>Value</th></tr></thead><tbody>\(rows)</tbody></table>"
        }
        let html = """
        <!DOCTYPE html>
        <html><head><title>Mock Response</title></head>
        <body>\(body)</body>
        </html>
        """
        return Data(html.utf8)
    }

    // MARK: - Media Type Helpers

    private func isJSONMediaType(_ mediaType: String) -> Bool {
        let normalized = mediaType.lowercased()
        return normalized == "application/json" || normalized.hasSuffix("+json") || normalized.contains("/json")
    }

    private func isSVGMediaType(_ mediaType: String) -> Bool {
        let normalized = mediaType.lowercased()
        return normalized.hasPrefix("image/svg")
    }

    private func isXMLMediaType(_ mediaType: String) -> Bool {
        let normalized = mediaType.lowercased()
        if isSVGMediaType(normalized) { return false }
        return normalized == "application/xml" || normalized == "text/xml" || normalized.hasSuffix("+xml")
    }

    private func isHTMLMediaType(_ mediaType: String) -> Bool {
        let normalized = mediaType.lowercased()
        return normalized == "text/html" || normalized == "application/xhtml+xml"
    }

    private func isCSVMediaType(_ mediaType: String) -> Bool {
        mediaType.lowercased() == "text/csv"
    }

    private func isTextMediaType(_ mediaType: String) -> Bool {
        let normalized = mediaType.lowercased()
        return normalized.hasPrefix("text/")
            && !isXMLMediaType(normalized)
            && !isHTMLMediaType(normalized)
            && !isCSVMediaType(normalized)
    }

    private func stringify(_ value: Any) -> String {
        switch value {
        case let string as String:
            return string
        case let number as NSNumber:
            return number.stringValue
        case let bool as Bool:
            return bool ? "true" : "false"
        default:
            if JSONSerialization.isValidJSONObject(value),
               let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
            return String(describing: value)
        }
    }

    private func extractRequestRules(
        operation: OpenAPIKit.OpenAPI.Operation,
        pathParameters: OpenAPIKit.OpenAPI.Parameter.Array,
        parameterComponents: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Parameter],
        requestBodyComponents: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Request]
    ) -> (
        requiredQueryParameters: [String],
        requiredHeaders: [String],
        requiresBody: Bool,
        acceptedContentTypes: [String]
    ) {
        let allParameters = pathParameters + operation.parameters
        var requiredQuery = Set<String>()
        var requiredHeaders = Set<String>()

        for parameterEither in allParameters {
            guard let parameter = resolveParameter(parameterEither, from: parameterComponents) else {
                continue
            }
            switch parameter.context {
            case .query(let required, _, _):
                if required { requiredQuery.insert(parameter.name) }
            case .header(let required, _):
                if required { requiredHeaders.insert(parameter.name) }
            default:
                continue
            }
        }

        let resolvedRequestBody = resolveRequestBody(operation.requestBody, from: requestBodyComponents)
        let requiresBody = resolvedRequestBody?.required ?? false
        let acceptedContentTypes = resolvedRequestBody?.content.map(\.key.rawValue) ?? []

        return (
            requiredQueryParameters: requiredQuery.sorted(),
            requiredHeaders: requiredHeaders.sorted(),
            requiresBody: requiresBody,
            acceptedContentTypes: acceptedContentTypes.sorted()
        )
    }

    private func resolveParameter(
        _ parameterEither: Either<OpenAPIKit.OpenAPI.Reference<OpenAPIKit.OpenAPI.Parameter>, OpenAPIKit.OpenAPI.Parameter>,
        from components: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Parameter]
    ) -> OpenAPIKit.OpenAPI.Parameter? {
        if let parameter = parameterEither.parameterValue {
            return parameter
        }

        guard case .a(let reference) = parameterEither else { return nil }
        guard let name = reference.name,
              let key = OpenAPIKit.OpenAPI.ComponentKey(rawValue: name),
              let parameter = components[key] else {
            return nil
        }
        return parameter
    }

    private func resolveRequestBody(
        _ requestBodyEither: Either<OpenAPIKit.OpenAPI.Reference<OpenAPIKit.OpenAPI.Request>, OpenAPIKit.OpenAPI.Request>?,
        from components: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Request]
    ) -> OpenAPIKit.OpenAPI.Request? {
        guard let requestBodyEither else { return nil }

        if let requestBody = requestBodyEither.requestValue {
            return requestBody
        }

        guard case .a(let reference) = requestBodyEither else { return nil }
        guard let name = reference.name,
              let key = OpenAPIKit.OpenAPI.ComponentKey(rawValue: name),
              let requestBody = components[key] else {
            return nil
        }
        return requestBody
    }

    private func extractSecuritySchemes(
        from document: OpenAPIKit.OpenAPI.Document
    ) -> [String: OpenAPIKit.OpenAPI.SecurityScheme] {
        var schemes: [String: OpenAPIKit.OpenAPI.SecurityScheme] = [:]
        for (componentKey, schemeEither) in document.components.securitySchemes {
            if let scheme = schemeEither.securitySchemeValue {
                schemes[componentKey.rawValue] = scheme
            }
        }
        return schemes
    }

    private func extractParameters(
        from document: OpenAPIKit.OpenAPI.Document
    ) -> [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Parameter] {
        var parameters: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Parameter] = [:]
        for (componentKey, parameterEither) in document.components.parameters {
            if let parameter = parameterEither.parameterValue {
                parameters[componentKey] = parameter
            }
        }
        return parameters
    }

    private func extractRequestBodies(
        from document: OpenAPIKit.OpenAPI.Document
    ) -> [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Request] {
        var requests: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Request] = [:]
        for (componentKey, requestEither) in document.components.requestBodies {
            if let request = requestEither.requestValue {
                requests[componentKey] = request
            }
        }
        return requests
    }

    private func extractResponses(
        from document: OpenAPIKit.OpenAPI.Document
    ) -> [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Response] {
        var responses: [OpenAPIKit.OpenAPI.ComponentKey: OpenAPIKit.OpenAPI.Response] = [:]
        for (componentKey, responseEither) in document.components.responses {
            if let response = responseEither.responseValue {
                responses[componentKey] = response
            }
        }
        return responses
    }

    private func resolveAuth(
        operation: OpenAPIKit.OpenAPI.Operation,
        globalSecurity: [OpenAPIKit.OpenAPI.SecurityRequirement],
        securitySchemes: [String: OpenAPIKit.OpenAPI.SecurityScheme]
    ) -> AuthRequirement {
        let security = operation.security ?? globalSecurity
        if security.isEmpty {
            return .none
        }

        let alternatives = security.compactMap { requirement in
            resolveSecurityRequirement(requirement, securitySchemes: securitySchemes)
        }
        if alternatives.isEmpty {
            return .none
        }
        if alternatives.count == 1 {
            return alternatives[0]
        }
        return .anyOf(alternatives)
    }

    private func resolveSecurityRequirement(
        _ requirement: OpenAPIKit.OpenAPI.SecurityRequirement,
        securitySchemes: [String: OpenAPIKit.OpenAPI.SecurityScheme]
    ) -> AuthRequirement? {
        let allSchemes: [AuthRequirement] = requirement.compactMap { pair -> AuthRequirement? in
            let schemeRef = pair.key
            let scopes = pair.value
            guard let schemeName = schemeRef.name,
                  let scheme = securitySchemes[schemeName] else {
                return nil
            }

            switch scheme.type {
            case .http(let schemeType, _):
                switch schemeType.lowercased() {
                case "bearer":
                    return .bearer
                case "basic":
                    return .basic
                default:
                    return nil
                }
            case .apiKey(let name, _):
                return .apiKey(headerName: name)
            case .oauth2:
                return .oauth2(scopes: scopes)
            case .openIdConnect:
                return .openIdConnect(scopes: scopes)
            case .mutualTLS:
                return nil
            }
        }

        if allSchemes.isEmpty {
            return nil
        }
        if allSchemes.count == 1 {
            return allSchemes[0]
        }
        return .allOf(allSchemes)
    }

    private func statusCodeToVariantName(_ statusCode: OpenAPIKit.OpenAPI.Response.StatusCode) -> String {
        switch statusCode.value {
        case .default:
            return "default"
        case .status(let code):
            if code >= 200 && code < 300 {
                return "default"
            } else if code >= 400 && code < 500 {
                return "error-\(code)"
            } else if code >= 500 {
                return "error-\(code)"
            }
            return "\(code)"
        case .range(let range):
            return range.rawValue
        }
    }
}

public enum OpenAPIParserError: Error, CustomStringConvertible {
    case unsupportedFormat

    public var description: String {
        switch self {
        case .unsupportedFormat:
            return "Unable to parse spec as OpenAPI 3.0 or 3.1"
        }
    }
}
