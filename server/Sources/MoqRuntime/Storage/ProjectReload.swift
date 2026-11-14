import Foundation
import Logging
import MoqCore
import Vapor

private let logger = Logger(label: "moqserver.runtime.ProjectReload")

/// A freshly loaded bundle, ready to replace what the server is serving.
public struct RuntimeProjectSnapshot: Sendable {
    public let endpoints: [Endpoint]
    /// Bundle scenarios: name → (`Endpoint.stateKey` → variant).
    public let scenarios: [String: [String: String]]

    public init(endpoints: [Endpoint], scenarios: [String: [String: String]]) {
        self.endpoints = endpoints
        self.scenarios = scenarios
    }
}

/// Loads the bundle again from disk; throws when it no longer loads or validates, in which case
/// the server keeps serving the previous version.
public typealias RuntimeProjectSource = @Sendable () async throws -> RuntimeProjectSnapshot

/// Result of `POST /_admin/reload`.
public struct ReloadSummary: Content, Equatable {
    public let endpoints: Int
    public let scenarios: Int
    public let sessions: Int
    /// Runtime state the new bundle could not keep (an override or scenario naming an endpoint
    /// or variant that no longer exists).
    public let warnings: [String]
}

public enum ProjectReloadError: Error, CustomStringConvertible {
    case notConfigured
    case loadFailed(String)

    public var description: String {
        switch self {
        case .notConfigured: return "Reload is not available: the server was not started from a project path."
        case .loadFailed(let detail): return "Reload failed; still serving the previous project. \(detail)"
        }
    }
}

/// Serializes reloads and applies each one to both the route table and the store.
public actor MockReloader {
    let store: InMemoryMockStore
    let table: MockRouteTable
    let source: RuntimeProjectSource

    public init(store: InMemoryMockStore, table: MockRouteTable, source: @escaping RuntimeProjectSource) {
        self.store = store
        self.table = table
        self.source = source
    }

    public func reload() async throws -> ReloadSummary {
        logger.info("Reloading project")
        let snapshot: RuntimeProjectSnapshot
        do {
            snapshot = try await source()
        } catch {
            logger.error("Project reload failed", metadata: ["error": "\(error)"])
            throw ProjectReloadError.loadFailed("\(error)")
        }
        // Store first: a request routed by the new table must find its endpoint in the store.
        let summary = await store.replaceProject(snapshot)
        table.replace(with: snapshot.endpoints)
        for warning in summary.warnings {
            logger.warning("Reload: \(warning)")
        }
        logger.info(
            "Project reloaded",
            metadata: [
                "endpoints": "\(summary.endpoints)", "scenarios": "\(summary.scenarios)",
                "sessions": "\(summary.sessions)",
            ])
        return summary
    }
}

extension InMemoryMockStore {
    /// Swaps in a reloaded bundle here and in every live session. Overrides, scenario activation and
    /// call counts survive for endpoints and variants that still exist; the rest are dropped and
    /// reported. Bundle scenarios replace the previous definitions.
    func replaceProject(_ snapshot: RuntimeProjectSnapshot) async -> ReloadSummary {
        var warnings = applyProject(snapshot, scope: "global")
        let sessions = runtimeSessions
        for (id, session) in sessions.sorted(by: { $0.key < $1.key }) {
            warnings += await session.applyProject(snapshot, scope: "session \(id)")
        }
        return ReloadSummary(
            endpoints: snapshot.endpoints.count, scenarios: snapshot.scenarios.count,
            sessions: sessions.count, warnings: warnings)
    }

    func applyProject(_ snapshot: RuntimeProjectSnapshot, scope: String) -> [String] {
        var warnings: [String] = []
        endpoints.removeAll()
        graphqlEndpoints.removeAll()
        for endpoint in snapshot.endpoints {
            register(endpoint)
        }
        let byKey = Dictionary(allEndpoints().map { ($0.stateKey, $0) }, uniquingKeysWith: { first, _ in first })

        for (key, variant) in variantOverrides.sorted(by: { $0.key < $1.key })
        where byKey[key]?.variants.contains(where: { $0.matchesIdentifier(variant) }) != true {
            variantOverrides.removeValue(forKey: key)
            warnings.append("[\(scope)] dropped variant override \(key) → \(variant)")
        }
        for key in callCounts.keys where byKey[key] == nil {
            callCounts.removeValue(forKey: key)
        }

        var reloadedScenarios: [String: RuntimeScenario] = [:]
        for (name, overrides) in snapshot.scenarios {
            let valid = overrides.allSatisfy { key, variant in
                byKey[key]?.variants.contains(where: { $0.matchesIdentifier(variant) }) == true
            }
            if valid {
                reloadedScenarios[name] = RuntimeScenario(name: name, overrides: overrides)
            } else {
                warnings.append("[\(scope)] skipped scenario \(name): it names an unknown endpoint or variant")
            }
        }
        scenarios = reloadedScenarios
        if let activeScenario, scenarios[activeScenario] == nil {
            warnings.append("[\(scope)] active scenario \(activeScenario) no longer exists")
            self.activeScenario = nil
        }
        persistVariantOverridesIfNeeded()
        return warnings
    }
}
