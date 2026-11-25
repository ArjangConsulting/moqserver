import Foundation
import Logging
import MoqCore

private let logger = Logger(label: "moqserver.import.HARImporter")

/// Parses HAR 1.2 files into a `ParsedSpec`. Studio imports HAR through `moq-format`'s
/// `import.parseHar`, so this is the only HAR parser.
///
/// Security: all sensitive values (auth headers, cookies, tokens, JWTs) are redacted at parse
/// time following the same approach as Cloudflare's HAR Sanitizer
/// (https://github.com/cloudflare/har-sanitizer). Values are replaced with `[redacted]` so they
/// never reach the parsed spec or a persisted `.moqproj`. Which headers count as credentials is
/// decided by `CredentialNames`, shared with request-history capture and request validation.
public enum HARImporter {
    private static let contentTypeHeader = "Content-Type"
    private static let cookieHeader = "Cookie"
    private static let harImportSuffix = "HAR Import"
    private static let defaultVersion = "1.0"
    private static let validStatusRange = 100...599
    private static let defaultVariantName = "default"
    private static let successVariantPrefix = "success"
    private static let redirectVariantPrefix = "redirect"
    private static let errorVariantPrefix = "error"
    private static let redacted = "[redacted]"
    private static let encodedRedacted = "%5Bredacted%5D"
    private static let redactionWarning =
        "HAR sensitive fields were redacted heuristically. Review imported response bodies because arbitrary secret names may not be detected."

    /// Names HAR import also redacts in query strings, form fields and JSON bodies, on top of
    /// ``CredentialNames``. A capture is committed to a bundle, so import errs further than the
    /// runtime does: over-redacting here only turns a rule into a presence check, while at runtime
    /// it would stop a test checking an ordinary value such as `?code=PROMO10`.
    static let importOnlyParameters: Set<String> = Set(
        [
            "code", "state", "auth", "key", "sig", "signature", "client_id", "code_challenge", "challenge", "email",
            "authenticity_token", "samlrequest",
        ].map(CredentialNames.normalize))

    /// Response headers HAR import also redacts, on top of ``CredentialNames``.
    static let importOnlyHeaders: Set<String> = Set(
        ["x-forwarded-for", "www-authenticate"].map(CredentialNames.normalize))

    /// Parses `content`, redacting the built-in credential names plus `redactHeaders` and
    /// `redactQueryParameters`. See ``parse(_:credentialNames:inferHeaderRules:)``.
    public static func parse(
        _ content: String, redactHeaders: [String] = [], redactQueryParameters: [String] = [],
        inferHeaderRules: Bool = false
    ) throws -> ParsedSpec {
        try parse(
            content,
            credentialNames: CredentialNames(extraHeaders: redactHeaders, extraQueryParameters: redactQueryParameters),
            inferHeaderRules: inferHeaderRules)
    }

    /// Parses `content`. Response headers and request parameters `credentialNames` lists (plus
    /// ``importOnlyHeaders`` and ``importOnlyParameters``) are redacted, and JWT signatures are
    /// stripped everywhere. With `inferHeaderRules`, each endpoint requires (presence only, never a
    /// value) the request headers its captured calls always sent; see ``inferredRequiredHeaders(_:names:)``.
    public static func parse(
        _ content: String, credentialNames: CredentialNames, inferHeaderRules: Bool = false
    ) throws -> ParsedSpec {
        let names = credentialNames
        logger.info("Parsing HAR file (\(content.utf8.count) bytes)")
        let har: HarFile
        do {
            har = try JSONDecoder().decode(HarFile.self, from: Data(content.utf8))
        } catch {
            throw HARImportError.invalidHAR("Unable to parse HAR file: \(error)")
        }
        let entries = har.log?.entries ?? []
        guard !entries.isEmpty else {
            throw HARImportError.noImportableEntries("HAR file does not contain any importable HTTP entries.")
        }

        var grouped: [GroupKey: [CapturedExchange]] = [:]
        var groupOrder: [GroupKey] = []
        var warnings: [String] = []
        var redactedResponseBody = false

        for (index, entry) in entries.enumerated() {
            guard
                let parsedEntry = parseEntry(
                    entry, entryNumber: index + 1, names: names, warnings: &warnings)
            else { continue }
            redactedResponseBody = redactedResponseBody || parsedEntry.responseBodyRedacted
            if grouped[parsedEntry.key] == nil {
                grouped[parsedEntry.key] = []
                groupOrder.append(parsedEntry.key)
            }
            grouped[parsedEntry.key]?.append(parsedEntry.exchange)
        }
        if redactedResponseBody { warnings.append(redactionWarning) }

        let sortedKeys = groupOrder.sorted { lhs, rhs in
            lhs.path == rhs.path ? lhs.method < rhs.method : lhs.path < rhs.path
        }
        let endpoints: [ParsedEndpoint] = sortedKeys.map { key in
            let exchanges = grouped[key] ?? []
            let responses = makeResponses(exchanges)
            return ParsedEndpoint(
                method: key.method,
                path: key.path,
                alias: EndpointAlias.defaultAlias(method: key.method, path: key.path),
                responses: responses,
                queryParameters: collectRuleMatchers(exchanges) { $0.queryParameters },
                requiredHeaders: inferHeaderRules ? inferredRequiredHeaders(exchanges, names: names) : [],
                cookies: collectRuleMatchers(exchanges) { $0.cookies }
            )
        }

        guard !endpoints.isEmpty else {
            let prefix = "HAR file does not contain any importable HTTP entries."
            let message = warnings.first.map { "\(prefix) \($0)" } ?? prefix
            throw HARImportError.noImportableEntries(message)
        }

        if !warnings.isEmpty {
            logger.warning("HAR parser skipped \(warnings.count) malformed entries")
        }

        let title = har.log?.creator?.name.map { "\($0) \(harImportSuffix)" } ?? harImportSuffix
        let version = har.log?.creator?.version ?? har.log?.version ?? defaultVersion

        logger.info(
            "HAR parse complete: '\(title)' — \(endpoints.count) endpoint(s) from \(entries.count) raw entries")
        return ParsedSpec(title: title, version: version, endpoints: endpoints, warnings: warnings)
    }

    // MARK: - Entry parsing

    private static func normalizedPath(_ components: URLComponents) -> String {
        let path = components.path
        if path.isEmpty { return "/" }
        return path.hasPrefix("/") ? path : "/\(path)"
    }

    private static func parseEntry(
        _ entry: HarEntry, entryNumber: Int, names: CredentialNames, warnings: inout [String]
    ) -> ParsedHarEntry? {
        guard let request = entry.request else {
            return warnAndSkip(&warnings, entryNumber, "missing request payload")
        }
        guard let response = entry.response else {
            return warnAndSkip(&warnings, entryNumber, "missing response payload")
        }
        let method = (request.method ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        guard !method.isEmpty else {
            return warnAndSkip(&warnings, entryNumber, "missing request method")
        }
        let rawURL = (request.url ?? "").trimmingCharacters(in: .whitespaces)
        guard !rawURL.isEmpty else {
            return warnAndSkip(&warnings, entryNumber, "missing request URL")
        }
        guard let components = URLComponents(string: rawURL) else {
            return warnAndSkip(&warnings, entryNumber, "invalid request URL '\(rawURL)'")
        }

        guard let status = response.status, validStatusRange.contains(status) else {
            return warnAndSkip(
                &warnings, entryNumber,
                "missing or invalid HTTP status; transport failures cannot be replayed as success")
        }
        if entry._error != nil || response._capture?.isComplete == false
            || response._capture?.isTruncated == true
        {
            return warnAndSkip(&warnings, entryNumber, "incomplete or failed capture; re-capture before importing")
        }
        let sanitizedBody = responseBody(response, names: names)
        return ParsedHarEntry(
            key: GroupKey(method: method, path: normalizedPath(components)),
            exchange: CapturedExchange(
                statusCode: status,
                headers: responseHeaders(response, names: names),
                body: sanitizedBody.value,
                bodyIsBase64: sanitizedBody.isBase64,
                cookies: extractRequestCookies(request),
                queryParameters: requestQueryParameters(request, components, names: names),
                requestHeaderNames: requestHeaderNames(request)
            ),
            responseBodyRedacted: sanitizedBody.redacted
        )
    }

    private static func warnAndSkip(_ warnings: inout [String], _ entryNumber: Int, _ reason: String) -> ParsedHarEntry?
    {
        warnings.append("Skipped HAR entry \(entryNumber): \(reason).")
        return nil
    }

    /// Extracts response headers, redacting values for any header whose name matches a
    /// known-sensitive pattern. JWT signatures in non-redacted header values are also stripped.
    private static func responseHeaders(_ response: HarResponse, names: CredentialNames) -> [String: String] {
        var headers: [String: String] = [:]
        for header in response.headers {
            let name = header.name ?? ""
            guard !name.isEmpty else { continue }
            headers[name] = sanitizeHeaderValue(name: name, value: header.value ?? "", names: names)
        }
        let mimeType = response.content.mimeType ?? ""
        if !mimeType.isEmpty
            && !headers.keys.contains(where: { $0.caseInsensitiveCompare(contentTypeHeader) == .orderedSame })
        {
            headers[contentTypeHeader] = mimeType
        }
        return headers
    }

    private static func responseBody(_ response: HarResponse, names: CredentialNames) -> SanitizedBody {
        guard let text = response.content.text else { return SanitizedBody(value: nil, redacted: false) }

        if response.content.encoding?.lowercased() == "base64" {
            guard isLikelyTextualMimeType(response.content.mimeType) else {
                // Genuinely binary: keep the base64 as-is and mark it, so the resulting variant
                // declares body_encoding instead of a downstream writer treating this text as the
                // literal response body.
                return SanitizedBody(value: text, redacted: false, isBase64: true)
            }
            guard let decodedData = Data(base64Encoded: text), let decoded = String(data: decodedData, encoding: .utf8)
            else {
                return sanitizeStructuredBody(text, mimeType: response.content.mimeType, names: names)
            }
            return sanitizeStructuredBody(decoded, mimeType: response.content.mimeType, names: names)
        }
        return sanitizeStructuredBody(text, mimeType: response.content.mimeType, names: names)
    }

    /// Extracts request cookies, replacing every value with `[redacted]`. Cookie values are
    /// session credentials and must never appear in the persisted project.
    private static func extractRequestCookies(_ request: HarRequest) -> [String: String] {
        if !request.cookies.isEmpty {
            var result: [String: String] = [:]
            for cookie in request.cookies {
                let name = (cookie.name ?? "").trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                result[name] = redacted
            }
            return result
        }

        guard
            let headerValue = request.headers.first(where: {
                ($0.name ?? "").caseInsensitiveCompare(cookieHeader) == .orderedSame
            })?.value
        else {
            return [:]
        }

        var result: [String: String] = [:]
        for pair in headerValue.split(separator: ";", omittingEmptySubsequences: false) {
            guard let separatorIndex = pair.firstIndex(of: "=") else { continue }
            let name = pair[pair.startIndex..<separatorIndex].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            result[name] = redacted
        }
        return result
    }

    private static func makeResponses(_ exchanges: [CapturedExchange]) -> [ParsedResponse] {
        var usedNames: Set<String> = []
        var didAssignDefault = false

        return exchanges.map { exchange in
            let baseName = baseVariantName(statusCode: exchange.statusCode, allowDefault: !didAssignDefault)
            let name = uniqueImportName(baseName, usedNames)
            usedNames.insert(name)
            if name == defaultVariantName { didAssignDefault = true }

            return ParsedResponse(
                name: name, statusCode: exchange.statusCode, headers: exchange.headers, body: exchange.body,
                isBase64: exchange.bodyIsBase64)
        }
    }

    private static func uniqueImportName(_ base: String, _ used: Set<String>) -> String {
        guard used.contains(base) else { return base }
        var suffix = 2
        while used.contains("\(base)-\(suffix)") { suffix += 1 }
        return "\(base)-\(suffix)"
    }

    private static func requestQueryParameters(
        _ request: HarRequest, _ components: URLComponents, names: CredentialNames
    ) -> [String: String] {
        let items: [(name: String, value: String)]
        if !request.queryString.isEmpty {
            items = request.queryString.map { ($0.name ?? "", $0.value ?? "") }
        } else {
            let rawQuery = components.percentEncodedQuery ?? ""
            items =
                rawQuery
                .split(separator: "&", omittingEmptySubsequences: true)
                .compactMap { pair -> (String, String)? in
                    if pair.isEmpty { return nil }
                    if let separatorIndex = pair.firstIndex(of: "=") {
                        let rawName = String(pair[pair.startIndex..<separatorIndex])
                        let rawValue = String(pair[pair.index(after: separatorIndex)...])
                        return (urlDecode(rawName), urlDecode(rawValue))
                    }
                    return (urlDecode(String(pair)), "")
                }
        }

        var result: [String: String] = [:]
        for (rawName, rawValue) in items {
            let name = rawName.trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            result[name] = sanitizeParamValue(name: name, value: rawValue, names: names)
        }
        return result
    }

    // MARK: - Header rule inference

    /// Standard request headers that say nothing about what the API requires: transport, caching,
    /// negotiation and browser metadata. Cookies have their own rules; the mock-control headers are
    /// test plumbing.
    private static let ignoredInferenceHeaders: Set<String> = [
        "accept", "accept-encoding", "accept-language", "cache-control", "connection", "content-length",
        "content-type", "cookie", "dnt", "host", "if-modified-since", "if-none-match", "keep-alive", "origin",
        "pragma", "priority", "referer", "te", "upgrade-insecure-requests", "user-agent", "x-mock-session",
        "x-mock-variant", "x-requested-with",
    ]

    /// Header names to require on an endpoint: those sent on every successful (2xx) captured call,
    /// or on every call when none succeeded, that are credentials (`names`) or custom
    /// `X-` headers. Sorted, in their captured spelling.
    private static func inferredRequiredHeaders(_ exchanges: [CapturedExchange], names: CredentialNames) -> [String] {
        let successful = exchanges.filter { (200...299).contains($0.statusCode) }
        let basis = successful.isEmpty ? exchanges : successful
        guard let first = basis.first else { return [] }
        var common = Set(first.requestHeaderNames.keys)
        for exchange in basis.dropFirst() { common.formIntersection(exchange.requestHeaderNames.keys) }
        let kept = common.filter { name in
            !name.hasPrefix(":") && !name.hasPrefix("sec-") && !ignoredInferenceHeaders.contains(name)
                && (names.isCredentialHeader(name) || name.hasPrefix("x-"))
        }
        return kept.sorted().map { first.requestHeaderNames[$0] ?? $0 }
    }

    private static func requestHeaderNames(_ request: HarRequest) -> [String: String] {
        var names: [String: String] = [:]
        for header in request.headers {
            let name = (header.name ?? "").trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            names[name.lowercased()] = names[name.lowercased()] ?? name
        }
        return names
    }

    private static func collectRuleMatchers(
        _ exchanges: [CapturedExchange], _ selector: (CapturedExchange) -> [String: String]
    ) -> [RuleMatcher] {
        var valuesByName: [String: Set<String>] = [:]
        var order: [String] = []
        for exchange in exchanges {
            for (name, value) in selector(exchange) {
                if valuesByName[name] == nil {
                    valuesByName[name] = []
                    order.append(name)
                }
                valuesByName[name]?.insert(value)
            }
        }
        return order.map { name in
            let values = valuesByName[name] ?? []
            // A redacted value is a placeholder, not what the app sends: matching it would reject
            // every real request, so a redacted cookie or parameter only has to be present.
            let match = values.count == 1 ? values.first.flatMap { isRedactedPlaceholder($0) ? nil : $0 } : nil
            return RuleMatcher(name: name, match: match, required: true, matchType: .equalTo)
        }
    }

    private static func isRedactedPlaceholder(_ value: String) -> Bool {
        value == redacted || value == encodedRedacted || value.hasSuffix(".redacted")
    }

    private static func baseVariantName(statusCode: Int, allowDefault: Bool) -> String {
        switch statusCode {
        case 200...299:
            return allowDefault ? defaultVariantName : "\(successVariantPrefix)-\(statusCode)"
        case 300...399:
            return "\(redirectVariantPrefix)-\(statusCode)"
        default:
            return "\(errorVariantPrefix)-\(statusCode)"
        }
    }

    // MARK: - Sanitization

    /// Returns `[redacted]` if the header name marks a credential, otherwise strips any JWT
    /// signatures from the value and returns the result.
    private static func sanitizeHeaderValue(name: String, value: String, names: CredentialNames) -> String {
        if names.isExemptHeader(name) { return value }
        if names.isCredentialHeader(name) || importOnlyHeaders.contains(CredentialNames.normalize(name)) {
            return redacted
        }
        return redactJWT(value)
    }

    /// Returns `[redacted]` if the param name matches a known-sensitive token name, otherwise
    /// strips JWT signatures from the value.
    private static func sanitizeParamValue(name: String, value: String, names: CredentialNames) -> String {
        if names.isExemptQueryParameter(name) { return value }
        if isSensitiveParameter(name, names: names) {
            return redacted
        }
        return redactJWT(value)
    }

    /// Whether a query parameter, form field or JSON key holds a credential.
    private static func isSensitiveParameter(_ name: String, names: CredentialNames) -> Bool {
        guard !names.isExemptQueryParameter(name) else { return false }
        return names.isCredentialQueryParameter(name)
            || importOnlyParameters.contains(CredentialNames.normalize(name))
    }

    /// Replaces the cryptographic signature in any JWT found in `value` with "redacted",
    /// leaving the header and payload decodable for debugging. Returns `value` unchanged if no
    /// JWT is present.
    private static func redactJWT(_ value: String) -> String {
        value.replacingOccurrences(
            of: CredentialNames.jwtPattern, with: "$1.$2.redacted", options: .regularExpression)
    }

    private static func sanitizeStructuredBody(
        _ text: String, mimeType: String?, names: CredentialNames
    ) -> SanitizedBody {
        let mime = (mimeType ?? "").lowercased()
        if mime.contains("json") {
            guard let data = text.data(using: .utf8),
                let object = try? JSONSerialization.jsonObject(
                    with: data, options: [.fragmentsAllowed, .mutableContainers, .mutableLeaves])
            else {
                return SanitizedBody(value: text, redacted: false)
            }
            let (sanitized, redactedFlag) = sanitizeJSON(object, names: names)
            guard
                let sanitizedData = try? JSONSerialization.data(
                    withJSONObject: sanitized, options: [.fragmentsAllowed]),
                let sanitizedText = String(data: sanitizedData, encoding: .utf8)
            else {
                return SanitizedBody(value: text, redacted: false)
            }
            return SanitizedBody(value: sanitizedText, redacted: redactedFlag)
        }
        if mime.contains("x-www-form-urlencoded") {
            var didRedact = false
            let pairs = text.split(separator: "&", omittingEmptySubsequences: false).map { pair -> String in
                guard let separatorIndex = pair.firstIndex(of: "=") else { return String(pair) }
                let rawName = String(pair[pair.startIndex..<separatorIndex])
                let decodedName = urlDecode(rawName)
                if isSensitiveParameter(decodedName, names: names) {
                    didRedact = true
                    return "\(rawName)=\(encodedRedacted)"
                }
                return String(pair)
            }
            return SanitizedBody(value: pairs.joined(separator: "&"), redacted: didRedact)
        }
        let sanitized = redactJWT(text)
        return SanitizedBody(value: sanitized, redacted: sanitized != text)
    }

    private static func sanitizeJSON(_ value: Any, names: CredentialNames) -> (value: Any, redacted: Bool) {
        if let dict = value as? [String: Any] {
            var didRedact = false
            var result: [String: Any] = [:]
            for (key, child) in dict {
                if isSensitiveParameter(key, names: names) {
                    didRedact = true
                    result[key] = redacted
                } else {
                    let (sanitizedChild, childRedacted) = sanitizeJSON(child, names: names)
                    didRedact = didRedact || childRedacted
                    result[key] = sanitizedChild
                }
            }
            return (result, didRedact)
        }
        if let array = value as? [Any] {
            var didRedact = false
            let result = array.map { element -> Any in
                let (sanitizedElement, childRedacted) = sanitizeJSON(element, names: names)
                didRedact = didRedact || childRedacted
                return sanitizedElement
            }
            return (result, didRedact)
        }
        if let string = value as? String {
            let sanitized = redactJWT(string)
            return (sanitized, sanitized != string)
        }
        return (value, false)
    }

    private static func urlDecode(_ value: String) -> String {
        let plusReplaced = value.replacingOccurrences(of: "+", with: " ")
        return plusReplaced.removingPercentEncoding ?? plusReplaced
    }

    private static func isLikelyTextualMimeType(_ mimeType: String?) -> Bool {
        let mime = (mimeType ?? "").lowercased()
        if mime.isEmpty { return true }
        return mime.hasPrefix("text/")
            || mime.contains("json")
            || mime.contains("xml")
            || mime.contains("javascript")
            || mime.contains("graphql")
            || mime.contains("x-www-form-urlencoded")
            || mime.contains("svg")
    }

    // MARK: - Internal model

    private struct GroupKey: Hashable {
        let method: String
        let path: String
    }

    private struct ParsedHarEntry {
        let key: GroupKey
        let exchange: CapturedExchange
        let responseBodyRedacted: Bool
    }

    private struct SanitizedBody {
        let value: String?
        let redacted: Bool
        let isBase64: Bool

        init(value: String?, redacted: Bool, isBase64: Bool = false) {
            self.value = value
            self.redacted = redacted
            self.isBase64 = isBase64
        }
    }

    private struct CapturedExchange {
        let statusCode: Int
        let headers: [String: String]
        let body: String?
        let bodyIsBase64: Bool
        let cookies: [String: String]
        let queryParameters: [String: String]
        /// Request header names as captured, keyed by lowercased name. Values are never kept.
        let requestHeaderNames: [String: String]
    }
}

public enum HARImportError: Error, CustomStringConvertible, Equatable {
    case invalidHAR(String)
    case noImportableEntries(String)

    public var description: String {
        switch self {
        case .invalidHAR(let message): return message
        case .noImportableEntries(let message): return message
        }
    }
}

// MARK: - HAR JSON model

struct HarFile: Decodable {
    let log: HarLog?
}

struct HarLog: Decodable {
    let version: String?
    let creator: HarCreator?
    let entries: [HarEntry]

    private enum CodingKeys: String, CodingKey { case version, creator, entries }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(String.self, forKey: .version)
        creator = try container.decodeIfPresent(HarCreator.self, forKey: .creator)
        entries = try container.decodeIfPresent([HarEntry].self, forKey: .entries) ?? []
    }
}

struct HarCreator: Decodable {
    let name: String?
    let version: String?
}

struct HarEntry: Decodable {
    let _error: String?
    let request: HarRequest?
    let response: HarResponse?
}

struct HarRequest: Decodable {
    let method: String?
    let url: String?
    let headers: [HarHeader]
    let cookies: [HarCookie]
    let queryString: [HarQuery]
    let postData: HarPostData?

    private enum CodingKeys: String, CodingKey { case method, url, headers, cookies, queryString, postData }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        method = try container.decodeIfPresent(String.self, forKey: .method)
        url = try container.decodeIfPresent(String.self, forKey: .url)
        headers = try container.decodeIfPresent([HarHeader].self, forKey: .headers) ?? []
        cookies = try container.decodeIfPresent([HarCookie].self, forKey: .cookies) ?? []
        queryString = try container.decodeIfPresent([HarQuery].self, forKey: .queryString) ?? []
        postData = try container.decodeIfPresent(HarPostData.self, forKey: .postData)
    }
}

struct HarCapture: Decodable {
    let isComplete: Bool?
    let isTruncated: Bool?
}

struct HarResponse: Decodable {
    let status: Int?
    let headers: [HarHeader]
    let content: HarContent
    let _capture: HarCapture?

    private enum CodingKeys: String, CodingKey { case status, headers, content, _capture }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        _capture = try container.decodeIfPresent(HarCapture.self, forKey: ._capture)
        status = try container.decodeIfPresent(Int.self, forKey: .status)
        headers = try container.decodeIfPresent([HarHeader].self, forKey: .headers) ?? []
        content = try container.decodeIfPresent(HarContent.self, forKey: .content) ?? HarContent()
    }
}

struct HarHeader: Decodable {
    let name: String?
    let value: String?
}

struct HarQuery: Decodable {
    let name: String?
    let value: String?
}

struct HarCookie: Decodable {
    let name: String?
    let value: String?
}

struct HarPostData: Decodable {
    let mimeType: String?
    let text: String?
}

struct HarContent: Decodable {
    let mimeType: String?
    let text: String?
    let encoding: String?

    init(mimeType: String? = nil, text: String? = nil, encoding: String? = nil) {
        self.mimeType = mimeType
        self.text = text
        self.encoding = encoding
    }
}
