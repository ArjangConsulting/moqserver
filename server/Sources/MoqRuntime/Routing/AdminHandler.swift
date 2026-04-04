import MoqCore
import Vapor

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
        let (endpoint, keyString) = try await resolveEndpoint(req: req)
        let activeVariant = await store.activeVariantOverride(for: keyString)

        return EndpointDetail(
            method: endpoint.key.method.rawValue,
            path: endpoint.key.path,
            authRequirement: authRequirementString(endpoint.authRequirement),
            variants: endpoint.variants.map { v in
                VariantDetail(
                    name: v.name,
                    statusCode: Int(v.statusCode.code),
                    hasBody: v.body != nil,
                    delay: v.delay
                )
            },
            activeVariant: activeVariant
        )
    }

    /// PUT /_admin/endpoints/:method/**/variant — set active variant.
    public func setVariant(req: Request) async throws -> MessageResponse {
        try requireAdminAuth(req: req)
        let (endpoint, keyString) = try await resolveEndpoint(req: req)
        let body = try req.content.decode(SetVariantRequest.self)

        guard let variant = endpoint.variants.first(where: { $0.matchesIdentifier(body.variant) }) else {
            throw Abort(.badRequest, reason: "Variant '\(body.variant)' not found for \(keyString)")
        }

        await store.setVariantOverride(for: keyString, variant: variant.name)
        return MessageResponse(message: "Active variant set to '\(variant.name)' for \(keyString)")
    }

    /// DELETE /_admin/endpoints/:method/**/variant — reset to default.
    public func resetVariant(req: Request) async throws -> MessageResponse {
        try requireAdminAuth(req: req)
        let (_, keyString) = try await resolveEndpoint(req: req)
        await store.resetVariantOverride(for: keyString)
        return MessageResponse(message: "Variant reset to default for \(keyString)")
    }

    // MARK: - Helpers

    private func resolveEndpoint(req: Request) async throws -> (Endpoint, String) {
        guard let method = req.parameters.get("method") else {
            throw Abort(.badRequest, reason: "Missing method parameter. Expected URL format: /_admin/endpoints/:method/path")
        }

        let catchall = req.parameters.getCatchall().joined(separator: "/")
        let pathSegments: String
        if (req.method == .PUT || req.method == .DELETE) && catchall.hasSuffix("/variant") {
            pathSegments = String(catchall.dropLast("/variant".count))
        } else {
            pathSegments = catchall
        }

        let apiPath = "/" + pathSegments
        let httpMethod = HTTPMethodValue(rawValue: method.uppercased())
        let keyString = "\(httpMethod.rawValue) \(apiPath)"

        guard let endpoint = await store.lookup(method: httpMethod, path: apiPath) else {
            let allEndpoints = await store.allEndpoints()
            let matchingPaths = allEndpoints
                .filter { $0.key.path == apiPath }
                .map { $0.key.method.rawValue }
            let hint: String
            if matchingPaths.isEmpty {
                hint = "Use GET /_admin/endpoints to list all available endpoints."
            } else {
                hint = "Path exists with methods: \(matchingPaths.joined(separator: ", ")). Requested: \(httpMethod.rawValue)"
            }
            throw Abort(.notFound, reason: "Endpoint not found: \(keyString). \(hint)")
        }

        return (endpoint, keyString)
    }

    private func authRequirementString(_ auth: AuthRequirement) -> String {
        switch auth {
        case .none: return "none"
        case .bearer: return "bearer"
        case .basic: return "basic"
        case .apiKey(let header): return "apiKey(\(header))"
        case .oauth2(let scopes): return scopes.isEmpty ? "oauth2" : "oauth2(\(scopes.joined(separator: ", ")))"
        case .openIdConnect(let scopes): return scopes.isEmpty ? "openIdConnect" : "openIdConnect(\(scopes.joined(separator: ", ")))"
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
               auth == "Bearer \(bearerToken)" {
                authenticated = true
            }
        }

        if let apiKey = admin.apiKey {
            let header = admin.apiKeyHeader ?? "X-Admin-Key"
            if req.headers.first(name: header) == apiKey {
                authenticated = true
            }
        }

        if authenticated {
            return
        }

        var headers = HTTPHeaders()
        for challenge in challenges {
            headers.add(name: "WWW-Authenticate", value: challenge)
        }
        throw Abort(.unauthorized, headers: headers, reason: "Admin authorization required")
    }
}
