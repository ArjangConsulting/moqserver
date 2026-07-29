import Foundation
import Logging
import MoqCore

/// Thread-safe in-memory endpoint storage using Swift concurrency.
public actor InMemoryMockStore: MockStoring {
    private let logger = Logger(label: "moqserver.runtime.InMemoryMockStore")
    private var endpoints: [EndpointKey: Endpoint] = [:]
    /// GraphQL endpoints stored separately: multiple endpoints can share the same key (POST /graphql).
    private var graphqlEndpoints: [EndpointKey: [Endpoint]] = [:]
    private var overridesPersistencePath: String?

    public init() {}

    public func register(_ endpoint: Endpoint) {
        let key = endpoint.key
        logger.debug("Registering endpoint \(key.method.rawValue) \(key.path)")

        if endpoint.operation != nil {
            var list = graphqlEndpoints[key] ?? []
            // Replace if same operation name already registered
            if let opName = endpoint.operation?.name {
                list.removeAll { $0.operation?.name == opName }
            }
            list.append(endpoint)
            graphqlEndpoints[key] = list
            return
        }

        endpoints[key] = endpoint
    }

    /// Looks up an endpoint by its template key (e.g. `GET /users/{id}`).
    /// Routing is handled by Vapor; callers pass the template path, not the actual request path.
    public func lookup(method: HTTPMethodValue, path: String) -> Endpoint? {
        let normalizedPath = path.hasPrefix("/") ? path : "/\(path)"
        logger.trace("Looking up endpoint \(method.rawValue) \(normalizedPath)")
        let key = EndpointKey(method: method, path: normalizedPath)
        return endpoints[key]
    }

    public func lookupGraphQL(
        method: HTTPMethodValue,
        path: String,
        operationName: String?,
        operationType: EndpointOperation.OperationType?,
        normalizedDocument: String?
    ) -> Endpoint? {
        let normalizedPath = path.hasPrefix("/") ? path : "/\(path)"
        let key = EndpointKey(method: method, path: normalizedPath)
        guard let list = graphqlEndpoints[key], !list.isEmpty else { return nil }

        // Match by operation name first (most specific)
        if let name = operationName {
            if let match = list.first(where: { $0.operation?.name == name }) {
                return match
            }
        }

        // Match by operation type only
        if let type = operationType {
            if let match = list.first(where: {
                $0.operation?.type == type && $0.operation?.name == nil
            }) {
                if let normalizedDocument,
                    let documentMatch = list.first(where: {
                        $0.operation?.type == type && $0.operation?.name == nil
                            && Self.normalizeGraphQLDocument($0.operation?.document) == normalizedDocument
                    })
                {
                    return documentMatch
                }
                return match
            }
        }

        if let normalizedDocument,
            let documentMatch = list.first(where: {
                Self.normalizeGraphQLDocument($0.operation?.document) == normalizedDocument
            })
        {
            return documentMatch
        }

        // Fall back to first registered GraphQL endpoint
        return list.first
    }

    public func allEndpoints() -> [Endpoint] {
        Array(endpoints.values) + graphqlEndpoints.values.flatMap { $0 }
    }

    public func mergeVariants(from mockEndpoint: Endpoint) {
        let key = mockEndpoint.key
        if var existing = endpoints[key] {
            var mergedVariants = existing.variants
            for mockVariant in mockEndpoint.variants {
                if let idx = mergedVariants.firstIndex(where: { $0.name == mockVariant.name }) {
                    mergedVariants[idx] = mockVariant
                } else {
                    mergedVariants.append(mockVariant)
                }
            }
            existing = Endpoint(
                key: key,
                authRequirement: existing.authRequirement,
                variants: mergedVariants,
                queryParamRules: existing.queryParamRules,
                headerRules: existing.headerRules,
                cookieRules: existing.cookieRules,
                verifyCookies: existing.verifyCookies,
                requiresBody: existing.requiresBody,
                acceptedContentTypes: existing.acceptedContentTypes,
                operation: existing.operation,
                network: existing.network
            )
            endpoints[key] = existing
        } else {
            register(mockEndpoint)
        }
    }

    // MARK: - Variant Overrides

    private var variantOverrides: [String: String] = [:]

    public func setVariantOverride(for key: String, variant: String) {
        logger.info("Setting variant override '\(variant)' for \(key)")
        variantOverrides[key] = variant
        persistVariantOverridesIfNeeded()
    }

    public func activeVariantOverride(for key: String) -> String? {
        variantOverrides[key]
    }

    public func resetVariantOverride(for key: String) {
        logger.info("Resetting variant override for \(key)")
        variantOverrides.removeValue(forKey: key)
        persistVariantOverridesIfNeeded()
    }

    public func allVariantOverrides() -> [String: String] {
        variantOverrides
    }

    public func clear() {
        logger.info("Clearing all endpoints and overrides")
        endpoints.removeAll()
        graphqlEndpoints.removeAll()
        variantOverrides.removeAll()
        persistVariantOverridesIfNeeded()
    }

    public func configureVariantOverridePersistence(path: String?) {
        guard let path else {
            logger.debug("Variant override persistence disabled")
            overridesPersistencePath = nil
            return
        }

        let expanded = (path as NSString).expandingTildeInPath
        overridesPersistencePath = expanded
        logger.debug("Variant override persistence configured at \(expanded)")
        loadPersistedOverridesIfAvailable()
    }

    private func loadPersistedOverridesIfAvailable() {
        guard let path = overridesPersistencePath else { return }
        let fileURL = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            variantOverrides = try JSONDecoder().decode([String: String].self, from: data)
        } catch {
            logger.warning(
                "Failed to load persisted variant overrides from \(path): \(error). Starting with no overrides.")
        }
    }

    private func persistVariantOverridesIfNeeded() {
        guard let path = overridesPersistencePath else { return }
        let fileURL = URL(fileURLWithPath: path)
        let directoryURL = fileURL.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(
                at: directoryURL, withIntermediateDirectories: true, attributes: nil)
            let data = try JSONEncoder().encode(variantOverrides)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            logger.warning(
                "Failed to persist variant overrides to \(path): \(error). Overrides will not survive a restart.")
        }
    }

    private static func normalizeGraphQLDocument(_ document: String?) -> String? {
        guard let document else { return nil }
        return EndpointOperation.normalizeDocument(document)
    }
}
