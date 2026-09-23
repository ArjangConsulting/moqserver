import Foundation
import Logging
import MoqCore

private let logger = Logger(label: "moqserver.addons.AddonCatalog")

/// The add-ons compiled into this build, keyed by id.
public struct AddonCatalog: Sendable {
    private let addonTypes: [String: any MoqAddon.Type]

    public init(_ types: [any MoqAddon.Type]) {
        self.addonTypes = Dictionary(types.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// A catalog with no add-ons.
    public static let empty = AddonCatalog([])

    public var ids: [String] { addonTypes.keys.sorted() }

    public func addonType(for id: String) -> (any MoqAddon.Type)? { addonTypes[id] }

    /// Builds the add-ons a bundle enables. Throws on an id this build doesn't have, or when an
    /// add-on refuses its config or environment. Validation should have caught the first two
    /// already; this is the serve-time backstop (an unknown id must never be silently dropped).
    public func activate(
        _ configs: [String: AnyCodableValue]?, environment: AddonEnvironment
    ) throws -> ActiveAddons {
        var addons: [any MoqAddon] = []
        for (id, config) in (configs ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard let type = addonTypes[id] else {
                let known = ids.isEmpty ? "none" : ids.joined(separator: ", ")
                throw AddonActivationError(
                    addonID: id, message: "Unknown add-on. Add-ons in this build: \(known).")
            }
            if let problem = type.validateConfig(config).first {
                let location = problem.field.map { " (\($0))" } ?? ""
                throw AddonActivationError(addonID: id, message: "Invalid config\(location): \(problem.message)")
            }
            logger.info("Activating add-on \(id)")
            addons.append(try type.init(config: config, environment: environment))
        }
        return ActiveAddons(addons)
    }
}

/// The add-ons active for one server, in id order. Values from H2 are passed around as
/// `AddonFacts`, keyed by add-on id.
public struct ActiveAddons: Sendable {
    public let addons: [any MoqAddon]

    public init(_ addons: [any MoqAddon]) {
        self.addons = addons.sorted { type(of: $0).id < type(of: $1).id }
    }

    public static let none = ActiveAddons([])

    public var isEmpty: Bool { addons.isEmpty }

    public subscript(id: String) -> (any MoqAddon)? {
        addons.first { type(of: $0).id == id }
    }

    /// H2: runs every add-on's `enrich`.
    public func facts(for request: AddonRequest) async -> AddonFacts {
        var values: [String: AnyCodableValue] = [:]
        for addon in addons {
            if let facts = await addon.enrich(request) {
                values[type(of: addon).id] = facts
            }
        }
        return AddonFacts(values)
    }

    /// H5: every spec must be satisfied by its add-on. A spec for an add-on that isn't active
    /// never matches (validation reports it as `E_ADDON_NOT_ENABLED`).
    public func matches(_ specs: [String: AnyCodableValue], facts: AddonFacts) -> Bool {
        for (id, spec) in specs {
            guard let addon = self[id], addon.matches(spec, facts: facts[id]) else { return false }
        }
        return true
    }

    /// H7: annotations keyed by add-on id; add-ons with nothing to record are omitted.
    public func traceAnnotations(facts: AddonFacts) -> [String: [String: String]] {
        var annotations: [String: [String: String]] = [:]
        for addon in addons {
            let id = type(of: addon).id
            let fields = addon.traceAnnotations(facts: facts[id])
            if !fields.isEmpty {
                annotations[id] = fields
            }
        }
        return annotations
    }
}

/// Read-only per-request facts from H2, keyed by add-on id.
public struct AddonFacts: Codable, Sendable, Equatable {
    public let values: [String: AnyCodableValue]

    public init(_ values: [String: AnyCodableValue] = [:]) {
        self.values = values
    }

    public subscript(id: String) -> AnyCodableValue? { values[id] }
}
