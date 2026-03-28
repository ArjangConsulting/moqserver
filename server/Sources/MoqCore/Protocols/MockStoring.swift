/// Protocol for endpoint storage and lookup.
public protocol MockStoring: Sendable {
    func register(_ endpoint: Endpoint) async
    func lookup(method: HTTPMethodValue, path: String) async -> Endpoint?
    /// Looks up a GraphQL endpoint by operation name and/or type.
    func lookupGraphQL(
        method: HTTPMethodValue,
        path: String,
        operationName: String?,
        operationType: EndpointOperation.OperationType?
    ) async -> Endpoint?
    func allEndpoints() async -> [Endpoint]
    func mergeVariants(from endpoint: Endpoint) async

    // Variant overrides
    func setVariantOverride(for key: String, variant: String) async
    func activeVariantOverride(for key: String) async -> String?
    func resetVariantOverride(for key: String) async
    func allVariantOverrides() async -> [String: String]

    // Lifecycle
    func clear() async
    func configureVariantOverridePersistence(path: String?) async
}
