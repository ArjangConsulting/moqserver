import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.AdminHandler")

/// Handles admin API requests for managing mock endpoints at runtime.
public struct AdminHandler: Sendable {
    let store: any MockStoring
    let config: ServerConfig?

    public init(store: any MockStoring, config: ServerConfig? = nil) {
        self.store = store
        self.config = config
    }

    /// GET /_admin/endpoints — list all endpoints with variants and active variant.
    public func listEndpoints(req: Request) async throws -> [EndpointListItem] {
        try requireAdminAuth(req: req)
        logger.info("Listing all endpoints")
        let endpoints = await store.allEndpoints()
        let overrides = await store.allVariantOverrides()

        return endpoints.map { endpoint in
            let key = "\(endpoint.key.method.rawValue) \(endpoint.key.path)"
            return EndpointListItem(
                method: endpoint.key.method.rawValue,
                path: endpoint.key.path,
                variants: endpoint.variants.map(\.name),
                activeVariant: overrides[key]
            )
        }.sorted { ($0.method + $0.path) < ($1.method + $1.path) }
    }

    /// GET /_admin/endpoints/:method/** — get endpoint details.
    public func getEndpoint(req: Request) async throws -> EndpointDetail {
        try requireAdminAuth(req: req)
        let (endpoint, keyString, _) = try await resolveEndpoint(req: req)
        logger.info("Getting endpoint details for \(keyString)")
        let activeVariant = await store.activeVariantOverride(for: keyString)
        let currentCallCount = await store.currentCallCount(for: keyString)

        return EndpointDetail(
            method: endpoint.key.method.rawValue,
            path: endpoint.key.path,
            authRequirement: authRequirementString(endpoint.authRequirement),
            variants: endpoint.variants.map { v in
                VariantDetail(
                    name: v.name,
                    statusCode: Int(v.statusCode.code),
                    hasBody: v.body != nil,
                    delay: v.delay,
                    callCount: v.callCount
                )
            },
            activeVariant: activeVariant,
            currentCallCount: currentCallCount,
            strictCallCount: endpoint.strictCallCount
        )
    }

    /// PUT /_admin/endpoints/:method/**/variant — set active variant.
    public func setVariant(req: Request) async throws -> MessageResponse {
        try requireAdminAuth(req: req)
        let (endpoint, keyString, _) = try await resolveEndpoint(req: req)
        let body = try req.content.decode(SetVariantRequest.self)

        guard let variant = endpoint.variants.first(where: { $0.matchesIdentifier(body.variant) }) else {
            throw Abort(.badRequest, reason: "Variant '\(body.variant)' not found for \(keyString)")
        }

        await store.setVariantOverride(for: keyString, variant: variant.name)
        logger.info("Active variant set to '\(variant.name)' for \(keyString)")
        return MessageResponse(message: "Active variant set to '\(variant.name)' for \(keyString)")
    }

    /// DELETE /_admin/endpoints/:method/**/variant — reset to default.
    /// DELETE /_admin/endpoints/:method/**/call-count — reset the call counter.
    /// Both ride the same route registration (Vapor's `**` catchall can only bind one handler
    /// per pattern), so the suffix is resolved and dispatched on here rather than via two routes.
    public func resetVariant(req: Request) async throws -> MessageResponse {
        try requireAdminAuth(req: req)
        let (_, keyString, subresource) = try await resolveEndpoint(req: req)
        switch subresource {
        case .callCount:
            await store.resetCallCount(for: keyString)
            logger.info("Call count reset for \(keyString)")
            return MessageResponse(message: "Call count reset for \(keyString)")
        case .variant, .none:
            await store.resetVariantOverride(for: keyString)
            logger.info("Variant reset to default for \(keyString)")
            return MessageResponse(message: "Variant reset to default for \(keyString)")
        }
    }

    // MARK: - Helpers

    /// Which trailing catchall segment (if any) a DELETE/PUT request targeted. Vapor cannot bind
    /// two routes to the same `**` catchall pattern, so `/variant` and `/call-count` are
    /// distinguished by string suffix here rather than as separate route registrations.
    private enum ResolvedSubresource: Sendable {
        case variant
        case callCount
        case none
    }

    private func resolveEndpoint(req: Request) async throws -> (Endpoint, String, ResolvedSubresource) {
        guard let method = req.parameters.get("method") else {
            throw Abort(
                .badRequest, reason: "Missing method parameter. Expected URL format: /_admin/endpoints/:method/path")
        }

        let catchall = req.parameters.getCatchall().joined(separator: "/")
        let pathSegments: String
        let subresource: ResolvedSubresource
        if (req.method == .PUT || req.method == .DELETE) && catchall.hasSuffix("/variant") {
            pathSegments = String(catchall.dropLast("/variant".count))
            subresource = .variant
        } else if req.method == .DELETE && catchall.hasSuffix("/call-count") {
            pathSegments = String(catchall.dropLast("/call-count".count))
            subresource = .callCount
        } else {
            pathSegments = catchall
            subresource = .none
        }

        let apiPath = "/" + pathSegments
        let httpMethod = HTTPMethodValue(rawValue: method.uppercased())
        let keyString = "\(httpMethod.rawValue) \(apiPath)"

        // Vapor's catchall drops the trailing empty segment, so `/v1/foo/` arrives as `/v1/foo`
        // and would never match a stored path that keeps its trailing slash. Try the literal
        // path first, then the trailing-slash variant, so both spellings address one endpoint.
        let candidatePaths = apiPath.hasSuffix("/") ? [apiPath, String(apiPath.dropLast())] : [apiPath, apiPath + "/"]
        var resolved: Endpoint?
        for candidate in candidatePaths {
            if let found = await store.lookup(method: httpMethod, path: candidate) {
                resolved = found
                break
            }
        }
        guard let endpoint = resolved else {
            let allEndpoints = await store.allEndpoints()
            let matchingPaths =
                allEndpoints
                .filter { $0.key.path == apiPath }
                .map { $0.key.method.rawValue }
            let hint: String
            if matchingPaths.isEmpty {
                hint = "Use GET /_admin/endpoints to list all available endpoints."
            } else {
                hint =
                    "Path exists with methods: \(matchingPaths.joined(separator: ", ")). Requested: \(httpMethod.rawValue)"
            }
            throw Abort(.notFound, reason: "Endpoint not found: \(keyString). \(hint)")
        }

        // Derive the key from the endpoint we actually resolved, not the requested spelling —
        // the call-count store is keyed on the endpoint's own path.
        let resolvedKey = "\(endpoint.key.method.rawValue) \(endpoint.key.path)"
        return (endpoint, resolvedKey, subresource)
    }

    private func authRequirementString(_ auth: AuthRequirement) -> String {
        switch auth {
        case .none: return "none"
        case .bearer: return "bearer"
        case .basic: return "basic"
        case .apiKey(let header): return "apiKey(\(header))"
        case .oauth2(let scopes): return scopes.isEmpty ? "oauth2" : "oauth2(\(scopes.joined(separator: ", ")))"
        case .openIdConnect(let scopes):
            return scopes.isEmpty ? "openIdConnect" : "openIdConnect(\(scopes.joined(separator: ", ")))"
        case .allOf(let requirements):
            return "allOf(\(requirements.map(authRequirementString).joined(separator: ", ")))"
        case .anyOf(let requirements):
            return "anyOf(\(requirements.map(authRequirementString).joined(separator: ", ")))"
        }
    }

    private func requireAdminAuth(req: Request) throws {
        guard let admin = config?.admin else { return }

        var authenticated = false
        var challenges: [String] = []

        if let bearerToken = admin.bearerToken {
            challenges.append("Bearer realm=\"mock-server-admin\"")
            if let auth = req.headers.first(name: .authorization),
                SecureCompare.equals(auth, "Bearer \(bearerToken)")
            {
                authenticated = true
            }
        }

        if let apiKey = admin.apiKey {
            let header = admin.apiKeyHeader ?? "X-Admin-Key"
            if let provided = req.headers.first(name: header),
                SecureCompare.equals(provided, apiKey)
            {
                authenticated = true
            }
        }

        if authenticated {
            return
        }

        logger.warning("Admin auth failed for \(req.method) \(req.url.path)")
        var headers = HTTPHeaders()
        for challenge in challenges {
            headers.add(name: "WWW-Authenticate", value: challenge)
        }
        throw Abort(.unauthorized, headers: headers, reason: "Admin authorization required")
    }
}
