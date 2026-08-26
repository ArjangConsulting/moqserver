/// Protocol for endpoint storage and lookup.
public protocol MockStoring: Sendable {
    func register(_ endpoint: Endpoint) async
    func lookup(method: HTTPMethodValue, path: String) async -> Endpoint?
    /// Looks up a GraphQL endpoint by operation name and/or type.
    func lookupGraphQL(
        method: HTTPMethodValue,
        path: String,
        operationName: String?,
        operationType: EndpointOperation.OperationType?,
        normalizedDocument: String?
    ) async -> Endpoint?
    func allEndpoints() async -> [Endpoint]
    func mergeVariants(from endpoint: Endpoint) async

    // Variant overrides
    func setVariantOverride(for key: String, variant: String) async
    func activeVariantOverride(for key: String) async -> String?
    func resetVariantOverride(for key: String) async
    func allVariantOverrides() async -> [String: String]

    // Call counts
    /// Increments and returns the call count for `key` (1-indexed: first call returns 1).
    func incrementCallCount(for key: String) async -> Int
    /// Current call count for `key` without incrementing (0 if never called).
    func currentCallCount(for key: String) async -> Int
    func resetCallCount(for key: String) async

    // Lifecycle
    func clear() async
    func configureVariantOverridePersistence(path: String?) async
}
