import Foundation

import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.MockHandler")

/// Handles incoming requests by matching them to registered endpoints
/// and selecting the appropriate response variant.
public struct MockHandler: Sendable {
    let store: any MockStoring
    let config: ServerConfig?
    let authValidator: any AuthValidating
    let requestValidator: any RequestValidating

    public init(
        store: any MockStoring,
        config: ServerConfig? = nil,
        authValidator: (any AuthValidating)? = nil,
        requestValidator: (any RequestValidating)? = nil
    ) {
        self.store = store
        self.config = config
        self.authValidator = authValidator ?? AuthValidator(config: config?.auth?.toAuthConfig())
        self.requestValidator = requestValidator ?? RequestValidator()
    }

    /// Called by the catch-all fallback route for paths not matched by any registered endpoint.
    public func handleNotFound(req: Request) async throws -> Response {
        logger.warning("No endpoint found for \(req.method) \(req.url.path)")
        let errorResponse = ErrorResponse(
            error: "No mock endpoint found for \(req.method) \(req.url.path)",
            code: "endpoint_not_found",
            detail: "Method: \(req.method.rawValue), Path: \(req.url.path)",
            hint: "Check available endpoints via GET /_admin/endpoints"
        )
        return Response(
            status: .notFound,
            headers: ["Content-Type": "application/json"],
            body: .init(data: errorResponse.jsonData())
        )
    }

    /// Called by per-endpoint routes. The `matchedKey` is the template key (e.g. `GET /users/{id}`)
    /// for the route that Vapor already matched, so no store lookup is needed for REST endpoints.
    /// GraphQL endpoints still go through the store for operation-level matching.
    public func handle(req: Request, matchedKey: EndpointKey) async throws -> Response {
        let method = HTTPMethodValue(rawValue: req.method.rawValue)
        let path = req.url.path
        logger.debug("Handling request \(req.method) \(path) → \(matchedKey.path)")

        // GraphQL endpoints share a single path; use operation-level store lookup.
        let endpoint: Endpoint
        if let resolved = try await resolveGraphQL(method: method, path: path, req: req) {
            endpoint = resolved
        } else if let stored = await store.lookup(method: matchedKey.method, path: matchedKey.path) {
            endpoint = stored
        } else {
            return try await handleNotFound(req: req)
        }

        // Auth validation — for API key auth read the custom header, not Authorization
        let authContext: AuthContext
        if case .apiKey(let headerName) = endpoint.authRequirement {
            authContext = AuthContext(authorizationHeader: req.headers.first(name: headerName))
        } else {
            authContext = AuthContext(authorizationHeader: req.headers.first(name: .authorization))
        }
        let authOutcome = authValidator.evaluate(endpoint.authRequirement, context: authContext)
        if let authError = authResponseFromOutcome(authOutcome) {
            logger.warning("Auth failed for \(req.method) \(req.url.path)")
            return authError
        }

        // Request validation
        let requestContext = RequestContext(
            queryParameters: extractQueryParameters(from: req),
            headers: extractHeaders(from: req),
            cookies: extractCookies(from: req),
            hasBody: (req.body.data?.readableBytes ?? 0) > 0,
            contentType: req.headers.first(name: .contentType)
        )
        if let validationError = requestValidator.validate(endpoint: endpoint, context: requestContext) {
            let errorResponse = ErrorResponse(
                error: validationError.message,
                code: validationError.code.rawValue,
                detail: "\(endpoint.key.method.rawValue) \(endpoint.key.path)"
            )
            return Response(
                status: Vapor.HTTPResponseStatus(statusCode: Int(validationError.statusCode.code)),
                headers: ["Content-Type": "application/json"],
                body: .init(data: errorResponse.jsonData())
            )
        }

        // Variant selection
        let endpointKeyString = "\(endpoint.key.method.rawValue) \(endpoint.key.path)"
        let variantName = resolveVariantName(
            header: req.headers.first(name: "X-Mock-Variant"),
            adminOverride: await store.activeVariantOverride(for: endpointKeyString),
            configOverride: config?.variantOverride(for: endpointKeyString)
        )
        logger.debug("Variant selection for \(endpointKeyString): requested=\(variantName ?? "default")")

        guard let variant = selectVariant(endpoint: endpoint, named: variantName, req: req) else {
            let availableNames = endpoint.variants.map(\.name).joined(separator: ", ")
            let requestedInfo: String
            if let variantName {
                requestedInfo = "Requested variant: '\(variantName)'"
            } else {
                requestedInfo = "No specific variant requested"
            }
            let acceptHeader = req.headers.first(name: .accept)
            let acceptInfo = acceptHeader.map { ", Accept: \($0)" } ?? ""
            let errorResponse = ErrorResponse(
                error: "No response variant available for \(endpointKeyString)",
                code: "variant_not_found",
                detail: "\(requestedInfo)\(acceptInfo). Available variants: [\(availableNames)]",
                hint: "Use X-Mock-Variant header or PUT /_admin/endpoints/:method/**/variant to select a variant"
            )
            return Response(
                status: .notFound,
                headers: ["Content-Type": "application/json"],
                body: .init(data: errorResponse.jsonData())
            )
        }

        if let packetLossResponse = simulatedPacketLossResponse(for: endpoint) {
            logger.info("Simulated packet loss for \(endpointKeyString)")
            return packetLossResponse
        }

        // Apply delay
        let delay = effectiveDelay(for: endpoint, variant: variant, endpointKeyString: endpointKeyString)
        if let delay, delay > 0 {
            logger.debug("Applying \(delay)s delay for \(endpointKeyString)")
            try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }

        // Build response
        var headers = HTTPHeaders()
        for (name, value) in variant.headers {
            headers.add(name: name, value: value)
        }

        let body: Response.Body
        if let data = variant.body {
            body = .init(data: data)
        } else {
            body = .empty
        }

        logger.debug("Responding \(variant.statusCode.code) for \(endpointKeyString) variant=\(variant.name)")
        return Response(
            status: Vapor.HTTPResponseStatus(statusCode: Int(variant.statusCode.code)),
            headers: headers,
            body: body
        )
    }

    private func authResponseFromOutcome(_ outcome: AuthOutcome) -> Response? {
        switch outcome {
        case .allowed:
            return nil
        case .unauthorized(let message, let challenge):
            return authErrorResponse(status: .unauthorized, message: message, wwwAuthenticate: challenge)
        case .forbidden(let message, let challenge):
            return authErrorResponse(status: .forbidden, message: message, wwwAuthenticate: challenge)
        }
    }

    private func authErrorResponse(status: Vapor.HTTPResponseStatus, message: String, wwwAuthenticate: String?) -> Response {
        var headers: HTTPHeaders = ["Content-Type": "application/json"]
        if let wwwAuthenticate {
            headers.add(name: "WWW-Authenticate", value: wwwAuthenticate)
        }
        let code = status == .forbidden ? "forbidden" : "unauthorized"
        let errorResponse = ErrorResponse(error: message, code: code)
        return Response(
            status: status,
            headers: headers,
            body: .init(data: errorResponse.jsonData())
        )
    }

    private func resolveVariantName(header: String?, adminOverride: String?, configOverride: String?) -> String? {
        header ?? adminOverride ?? configOverride
    }

    private func selectVariant(endpoint: Endpoint, named name: String?, req: Request) -> ResponseVariant? {
        let primaryCandidates: [ResponseVariant]
        if let name {
            primaryCandidates = endpoint.variants.filter { $0.matchesIdentifier(name) }
        } else {
            primaryCandidates = endpoint.variants
        }

        // Try variants with explicit requestMatch constraints first
        if let constrained = primaryCandidates.first(where: { $0.requestMatch != nil && variantMatches($0, req: req) }) {
            return constrained
        }

        // Content negotiation: pick variant matching the Accept header
        let acceptHeader = req.headers.first(name: .accept)
        if let accept = acceptHeader, name == nil {
            let acceptTypes = parseAcceptHeader(accept)
            let unconstrained = primaryCandidates.filter { $0.requestMatch == nil }
            if let negotiated = bestVariantForAccept(candidates: unconstrained, acceptTypes: acceptTypes) {
                return negotiated
            }
        }

        // Fall back to first matching variant
        if let matched = primaryCandidates.first(where: { variantMatches($0, req: req) }) {
            return matched
        }

        if let defaultVariant = endpoint.defaultVariant, variantMatches(defaultVariant, req: req) {
            return defaultVariant
        }

        return endpoint.variants.first(where: { variantMatches($0, req: req) })
    }

    // MARK: - Content Negotiation

    /// Parsed Accept header entry with quality factor.
    private struct AcceptEntry {
        let mediaType: String  // e.g. "application/json", "text/*", "*/*"
        let quality: Double    // 0.0–1.0 (default 1.0)
    }

    /// Parses an Accept header into entries sorted by quality (descending).
    private func parseAcceptHeader(_ header: String) -> [AcceptEntry] {
        header.split(separator: ",").compactMap { part in
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            let segments = trimmed.split(separator: ";").map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            guard let mediaType = segments.first else { return nil }
            var quality = 1.0
            for segment in segments.dropFirst() {
                if segment.lowercased().hasPrefix("q="),
                   let q = Double(segment.dropFirst(2)) {
                    quality = q
                }
            }
            return AcceptEntry(mediaType: mediaType.lowercased(), quality: quality)
        }.sorted { $0.quality > $1.quality }
    }

    /// Returns the best variant matching the Accept preference list.
    private func bestVariantForAccept(candidates: [ResponseVariant], acceptTypes: [AcceptEntry]) -> ResponseVariant? {
        for accept in acceptTypes {
            for variant in candidates {
                let variantType = variantContentType(variant)?.lowercased() ?? ""
                if mediaTypeMatches(variantType, accept: accept.mediaType) {
                    return variant
                }
            }
        }
        return nil
    }

    private func variantContentType(_ variant: ResponseVariant) -> String? {
        variant.headers.first(where: {
            $0.0.caseInsensitiveCompare("Content-Type") == .orderedSame
        })?.1
    }

    /// Checks if a variant's content type matches an Accept entry (supports wildcards).
    private func mediaTypeMatches(_ variantType: String, accept: String) -> Bool {
        if accept == "*/*" { return true }
        if variantType == accept { return true }

        let acceptParts = accept.split(separator: "/", maxSplits: 1).map(String.init)
        let variantParts = variantType.split(separator: ";").first.map {
            $0.trimmingCharacters(in: .whitespaces)
        }?.split(separator: "/", maxSplits: 1).map(String.init) ?? []

        guard acceptParts.count == 2, variantParts.count == 2 else { return false }

        if acceptParts[1] == "*" {
            return acceptParts[0] == variantParts[0]
        }

        return acceptParts[0] == variantParts[0] && acceptParts[1] == variantParts[1]
    }

    private func variantMatches(_ variant: ResponseVariant, req: Request) -> Bool {
        guard let match = variant.requestMatch else { return true }

        for (name, expected) in match.query {
            guard req.query[String.self, at: name] == expected else { return false }
        }

        for (name, expected) in match.headers {
            guard req.headers.first(name: name) == expected else { return false }
        }

        if let snippet = match.bodyContains {
            guard let bodyString = requestBodyString(req),
                  bodyString.contains(snippet) else {
                return false
            }
        }

        return true
    }

    private func requestBodyString(_ req: Request) -> String? {
        guard let bodyData = req.body.data else { return nil }
        return bodyData.getString(at: bodyData.readerIndex, length: bodyData.readableBytes)
    }

    private func extractQueryParameters(from req: Request) -> [String: String] {
        guard let queryString = req.url.query else { return [:] }
        var params: [String: String] = [:]
        for pair in queryString.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2 {
                let name = String(parts[0]).removingPercentEncoding ?? String(parts[0])
                let value = String(parts[1]).removingPercentEncoding ?? String(parts[1])
                params[name] = value
            } else if parts.count == 1 {
                let name = String(parts[0]).removingPercentEncoding ?? String(parts[0])
                params[name] = ""
            }
        }
        return params
    }

    private func extractHeaders(from req: Request) -> [String: String] {
        var headers: [String: String] = [:]
        for (name, value) in req.headers {
            headers[name.lowercased()] = value
        }
        return headers
    }

    private func extractCookies(from req: Request) -> [String: String] {
        var cookies: [String: String] = [:]
        for header in req.headers[canonicalForm: "Cookie"] {
            for component in header.split(separator: ";") {
                let parts = component.split(separator: "=", maxSplits: 1).map {
                    $0.trimmingCharacters(in: .whitespaces)
                }
                guard let name = parts.first, !name.isEmpty else { continue }
                let value = parts.count > 1 ? parts[1] : ""
                cookies[name] = value
            }
        }
        return cookies
    }

    private func effectiveDelay(for endpoint: Endpoint, variant: ResponseVariant, endpointKeyString: String) -> TimeInterval? {
        let variantDelay = variant.delay ?? config?.effectiveDelay(for: endpointKeyString) ?? 0
        let network = endpoint.network
        let latency = TimeInterval((network?.latencyMs ?? 0)) / 1000.0
        let jitterMs = network?.jitterMs ?? 0
        let jitter = jitterMs > 0 ? TimeInterval(Int.random(in: -jitterMs...jitterMs)) / 1000.0 : 0
        let total = max(0, variantDelay + latency + jitter)
        return total > 0 ? total : nil
    }

    private func simulatedPacketLossResponse(for endpoint: Endpoint) -> Response? {
        let percent = endpoint.network?.packetLossPercent ?? 0
        guard percent > 0 else { return nil }
        guard Double.random(in: 0..<100) < percent else { return nil }

        let errorResponse = ErrorResponse(
            error: "Simulated packet loss",
            code: "simulated_packet_loss",
            hint: "Reduce packet_loss_percent in the project network settings to disable this failure"
        )
        return Response(
            status: .serviceUnavailable,
            headers: ["Content-Type": "application/json"],
            body: .init(data: errorResponse.jsonData())
        )
    }

    // MARK: - GraphQL Resolution

    private func resolveGraphQL(method: HTTPMethodValue, path: String, req: Request) async throws -> Endpoint? {
        guard method.rawValue == "POST", let graphqlBody = parseGraphQLBody(req) else { return nil }
        let operationType = graphqlBody.inferOperationType()
        return await store.lookupGraphQL(
            method: method,
            path: path,
            operationName: graphqlBody.operationName,
            operationType: operationType,
            normalizedDocument: graphqlBody.normalizedDocument()
        )
    }

    /// Parses a GraphQL JSON request body: `{ "query": "...", "operationName": "...", "variables": {...} }`
    private func parseGraphQLBody(_ req: Request) -> GraphQLRequestBody? {
        guard let bodyData = req.body.data,
              let bytes = bodyData.getBytes(at: bodyData.readerIndex, length: bodyData.readableBytes),
              let json = try? JSONSerialization.jsonObject(with: Data(bytes)) as? [String: Any] else {
            return nil
        }
        // Must have a "query" field to be considered GraphQL
        guard let query = json["query"] as? String else { return nil }
        let operationName = json["operationName"] as? String
        return GraphQLRequestBody(query: query, operationName: operationName)
    }
}

/// Parsed GraphQL request body fields needed for operation matching.
struct GraphQLRequestBody {
    let query: String
    let operationName: String?

    /// Infers the operation type from the query string.
    func inferOperationType() -> EndpointOperation.OperationType? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("mutation") {
            return .mutation
        } else if trimmed.hasPrefix("subscription") {
            return .subscription
        } else if trimmed.hasPrefix("query") || trimmed.hasPrefix("{") {
            return .query
        }
        return nil
    }

    func normalizedDocument() -> String {
        query
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
