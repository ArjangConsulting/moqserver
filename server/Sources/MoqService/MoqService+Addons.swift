import Foundation
import MoqAddonKit
import MoqAddons
import MoqCore
import MoqFormat

/// The add-ons a bundle enables in `project.yml`, and the ones this build can enable.
public struct AddonState: Encodable, Equatable, Sendable {
    /// Enabled add-on id → its config, as written under `addons:`.
    public let enabled: [String: AnyCodableValue]
    /// Ids this build knows. `oauth-mock` is absent: the server config controls it, not bundles.
    public let available: [String]
}

extension MoqService {
    /// Lists the bundle's enabled add-ons and the ones this build provides.
    public func addonState(handle: String) async throws -> AddonState {
        let store = try await self.session(handle).currentStore()
        return Self.addonState(of: await store.currentProject)
    }

    /// Enables add-on `id` with `config`, replacing any config it already has. Refuses an id this
    /// build doesn't have, or a config the add-on rejects, before anything is written.
    public func enableAddon(
        handle: String, id: String, config: AnyCodableValue, autosave: Bool
    ) async throws -> AddonState {
        let session = try await self.session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject
        var addons = project.manifest.addons ?? [:]
        addons[id] = config
        let candidate = project.replacingAddons(addons)
        let problems = ProjectValidator().validate(candidate).filter { diagnostic in
            diagnostic.severity == .error && diagnostic.file == "project.yml"
                && (diagnostic.field == "addons.\(id)" || diagnostic.field?.hasPrefix("addons.\(id).") == true)
        }
        if let first = problems.first {
            throw MoqServiceError.addonRejected(
                code: first.code?.rawValue ?? "E_INVALID_ADDON_CONFIG",
                message: problems.map { describe($0, addonID: id) }.joined(separator: " "))
        }
        await store.replace(manifest: candidate.manifest, endpoints: candidate.endpoints)
        try await session.recordMutation(autosave: autosave)
        return Self.addonState(of: candidate)
    }

    /// Disables add-on `id`. Disabling one that isn't enabled changes nothing. Refuses while a
    /// variant's `request_match.addons` still names it, since the bundle would no longer validate.
    public func disableAddon(handle: String, id: String, autosave: Bool) async throws -> AddonState {
        let session = try await self.session(handle)
        let store = try await session.currentStore()
        let project = await store.currentProject
        guard var addons = project.manifest.addons, addons[id] != nil else { return Self.addonState(of: project) }
        let users = project.endpoints.filter { endpoint in
            endpoint.variants.contains { $0.requestMatch?.addons[id] != nil }
        }
        guard users.isEmpty else { throw MoqServiceError.addonInUse(id: id, endpointIDs: users.map { $0.id }.sorted()) }
        addons[id] = nil
        let candidate = project.replacingAddons(addons.isEmpty ? nil : addons)
        await store.replace(manifest: candidate.manifest, endpoints: candidate.endpoints)
        try await session.recordMutation(autosave: autosave)
        return Self.addonState(of: candidate)
    }

    /// `verify_signature: Required, …` for a problem with one config key, the message alone otherwise.
    private func describe(_ diagnostic: ValidationDiagnostic, addonID: String) -> String {
        let prefix = "addons.\(addonID)."
        guard let field = diagnostic.field, field.hasPrefix(prefix) else { return diagnostic.message }
        return "\(field.dropFirst(prefix.count)): \(diagnostic.message)"
    }

    static func addonState(of project: MoqProject) -> AddonState {
        AddonState(enabled: project.manifest.addons ?? [:], available: AddonCatalog.builtIn.ids)
    }
}

extension MoqProject {
    fileprivate func replacingAddons(_ addons: [String: AnyCodableValue]?) -> MoqProject {
        let manifest = ProjectManifest(
            version: self.manifest.version, name: self.manifest.name, description: self.manifest.description,
            defaults: self.manifest.defaults, globalRules: self.manifest.globalRules, addons: addons,
            scenarios: self.manifest.scenarios)
        return MoqProject(manifest: manifest, endpoints: endpoints, projectPath: projectPath)
    }
}
