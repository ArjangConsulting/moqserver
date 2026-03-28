import Foundation
import MoqCore

/// Thread-safe in-memory endpoint storage using Swift concurrency.
public actor InMemoryMockStore: MockStoring {
    private var endpoints: [EndpointKey: Endpoint] = [:]
    /// GraphQL endpoints stored separately: multiple endpoints can share the same key (POST /graphql).
    private var graphqlEndpoints: [EndpointKey: [Endpoint]] = [:]
    private var patterns: [(regex: NSRegularExpression, key: EndpointKey)] = []
    private var overridesPersistencePath: String?

    public init() {}

    public func register(_ endpoint: Endpoint) {
        let key = endpoint.key

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

        if key.path.contains("{") {
            let regexPattern = "^" + key.path
                .split(separator: "/")
                .map { segment in
                    let s = String(segment)
                    if s.hasPrefix("{") && s.hasSuffix("}") {
                        return "[^/]+"
                    }
                    return NSRegularExpression.escapedPattern(for: s)
                }
                .joined(separator: "/")
            + "$"

            let fullPattern = "/" + regexPattern.dropFirst()
            if let regex = try? NSRegularExpression(pattern: fullPattern) {
                patterns.removeAll { $0.key == key }
                patterns.append((regex: regex, key: key))
            }
        }
    }

    public func lookup(method: HTTPMethodValue, path: String) -> Endpoint? {
        let normalizedPath = path.hasPrefix("/") ? path : "/\(path)"

        let exactKey = EndpointKey(method: method, path: normalizedPath)
        if let endpoint = endpoints[exactKey] {
            return endpoint
        }

        for (regex, key) in patterns where key.method == method {
            let range = NSRange(normalizedPath.startIndex..., in: normalizedPath)
            if regex.firstMatch(in: normalizedPath, range: range) != nil {
                return endpoints[key]
            }
        }

        return nil
    }

    public func lookupGraphQL(
        method: HTTPMethodValue,
        path: String,
        operationName: String?,
        operationType: EndpointOperation.OperationType?
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
                return match
            }
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
                requiredQueryParameters: existing.requiredQueryParameters,
                requiredHeaders: existing.requiredHeaders,
                requiresBody: existing.requiresBody,
                acceptedContentTypes: existing.acceptedContentTypes
            )
            endpoints[key] = existing
        } else {
            register(mockEndpoint)
        }
    }

    // MARK: - Variant Overrides

    private var variantOverrides: [String: String] = [:]

    public func setVariantOverride(for key: String, variant: String) {
        variantOverrides[key] = variant
        persistVariantOverridesIfNeeded()
    }

    public func activeVariantOverride(for key: String) -> String? {
        variantOverrides[key]
    }

    public func resetVariantOverride(for key: String) {
        variantOverrides.removeValue(forKey: key)
        persistVariantOverridesIfNeeded()
    }

    public func allVariantOverrides() -> [String: String] {
        variantOverrides
    }

    public func clear() {
        endpoints.removeAll()
        graphqlEndpoints.removeAll()
        patterns.removeAll()
        variantOverrides.removeAll()
        persistVariantOverridesIfNeeded()
    }

    public func configureVariantOverridePersistence(path: String?) {
        guard let path else {
            overridesPersistencePath = nil
            return
        }

        let expanded = (path as NSString).expandingTildeInPath
        overridesPersistencePath = expanded
        loadPersistedOverridesIfAvailable()
    }

    private func loadPersistedOverridesIfAvailable() {
        guard let path = overridesPersistencePath else { return }
        let fileURL = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              let loaded = try? JSONDecoder().decode([String: String].self, from: data) else {
            return
        }
        variantOverrides = loaded
    }

    private func persistVariantOverridesIfNeeded() {
        guard let path = overridesPersistencePath else { return }

        let fileURL = URL(fileURLWithPath: path)
        let directoryURL = fileURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true, attributes: nil)

        if let data = try? JSONEncoder().encode(variantOverrides) {
            try? data.write(to: fileURL)
        }
    }
}
