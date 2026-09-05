import Foundation
import MoqCore

public struct RuntimeScenario: Codable, Sendable {
    public let name: String
    public let overrides: [String: String]

    public init(name: String, overrides: [String: String]) {
        self.name = name
        self.overrides = overrides
    }
}

public struct RequestTrace: Codable, Sendable {
    public let id: String
    public let timestamp: Double
    public let method: String
    public let path: String
    public let endpoint: String?
    public let status: Int
    public let variant: String?
    public let reason: String
    public let callNumber: Int?
}

public enum RuntimeScenarioError: Error, Sendable {
    case invalidName
    case invalidOverride(String)
    case notFound
    case capacityExceeded
}

extension InMemoryMockStore {
    public func defineScenario(_ scenario: RuntimeScenario) throws {
        guard !scenario.name.isEmpty, scenario.name.count <= 100 else { throw RuntimeScenarioError.invalidName }
        guard scenarios[scenario.name] != nil || scenarios.count < 100 else {
            throw RuntimeScenarioError.capacityExceeded
        }
        _ = try validatedOverrides(scenario.overrides)
        scenarios[scenario.name] = scenario
    }

    public func removeScenario(_ name: String) { scenarios.removeValue(forKey: name) }

    public func allScenarios() -> [RuntimeScenario] { scenarios.values.sorted { $0.name < $1.name } }

    public func activateScenario(_ name: String) throws {
        guard let scenario = scenarios[name] else { throw RuntimeScenarioError.notFound }
        let validated = try validatedOverrides(scenario.overrides)
        // No suspension: requests observe either the old or new overrides and counters together.
        variantOverrides = validated
        callCounts.removeAll()
        activeScenario = name
        persistVariantOverridesIfNeeded()
    }

    public func resetRuntimeState(for key: String? = nil) {
        if let key {
            variantOverrides.removeValue(forKey: key)
            callCounts.removeValue(forKey: key)
        } else {
            variantOverrides.removeAll()
            callCounts.removeAll()
        }
        activeScenario = nil
        persistVariantOverridesIfNeeded()
    }

    public func beginRequest(for key: String) -> (count: Int, override: String?) {
        (incrementCallCount(for: key), variantOverrides[key])
    }

    public func recordRequest(_ trace: RequestTrace) {
        requestHistory.append(trace)
        if requestHistory.count > 500 { requestHistory.removeFirst(requestHistory.count - 500) }
    }

    public func recentRequests() -> [RequestTrace] { requestHistory.reversed() }
    public func clearRequestHistory() { requestHistory.removeAll() }

    public func createRuntimeSession() throws -> String {
        guard runtimeSessions.count < 64 else { throw RuntimeScenarioError.capacityExceeded }
        let id = UUID().uuidString
        runtimeSessions[id] = InMemoryMockStore(
            endpoints: endpoints, graphqlEndpoints: graphqlEndpoints, scenarios: scenarios)
        return id
    }

    public func runtimeSession(_ id: String) -> InMemoryMockStore? { runtimeSessions[id] }
    public func removeRuntimeSession(_ id: String) { runtimeSessions.removeValue(forKey: id) }

    private func validatedOverrides(_ overrides: [String: String]) throws -> [String: String] {
        var result: [String: String] = [:]
        for (key, name) in overrides {
            // GraphQL operations sharing a route are not independently addressable by this key.
            guard let endpoint = endpoints.values.first(where: { "\($0.key.method.rawValue) \($0.key.path)" == key }),
                let variant = endpoint.variants.first(where: { $0.matchesIdentifier(name) })
            else { throw RuntimeScenarioError.invalidOverride(key) }
            result[key] = variant.name
        }
        return result
    }
}
