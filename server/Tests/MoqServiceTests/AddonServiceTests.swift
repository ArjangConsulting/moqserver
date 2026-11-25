import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat
@testable import MoqService

struct AddonServiceTests {
    let service = MoqService()
    private let jwtConfig = AnyCodableValue.object(["verify_signature": .bool(false)])

    private func newProject() async throws -> (handle: String, path: String) {
        let handle = await service.openSession()
        let path = (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("addon-service-\(UUID().uuidString).moqproj")
        let manifest = ProjectManifest(
            name: "Addons",
            defaults: ProjectDefaults(auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()))
        _ = try await service.createProject(handle: handle, manifest: manifest, path: path, force: false)
        return (handle, path)
    }

    private func reopened(_ path: String) throws -> MoqProject { try ProjectLoader().load(from: path) }

    @Test("enable writes the config to project.yml and the bundle validates")
    func enableWrites() async throws {
        let (handle, path) = try await newProject()
        defer { try? FileManager.default.removeItem(atPath: path) }

        let state = try await service.enableAddon(handle: handle, id: "jwt-claims", config: jwtConfig, autosave: true)
        #expect(state.enabled == ["jwt-claims": jwtConfig])
        #expect(state.available.contains("jwt-claims"))
        let saved = try reopened(path)
        #expect(saved.manifest.addons == ["jwt-claims": jwtConfig])
        let addonProblems = ProjectValidator().validate(saved).filter { $0.field?.hasPrefix("addons") == true }
        #expect(addonProblems.isEmpty)
    }

    @Test("An unknown id or a config the add-on refuses is not written")
    func enableRefuses() async throws {
        let (handle, path) = try await newProject()
        defer { try? FileManager.default.removeItem(atPath: path) }

        await #expect(
            throws: MoqServiceError.addonRejected(
                code: "E_UNKNOWN_ADDON", message: "Unknown add-on \"nope\". Add-ons in this build: jwt-claims.")
        ) {
            _ = try await service.enableAddon(handle: handle, id: "nope", config: .object([:]), autosave: true)
        }
        do {
            _ = try await service.enableAddon(handle: handle, id: "jwt-claims", config: .object([:]), autosave: true)
            Issue.record("an empty jwt-claims config must be refused")
        } catch let MoqServiceError.addonRejected(code, message) {
            #expect(code == "E_INVALID_ADDON_CONFIG")
            #expect(message.hasPrefix("verify_signature: "))
        }
        #expect(try reopened(path).manifest.addons == nil)
    }

    @Test("disable refuses while a variant matches on the add-on, then removes it")
    func disableGuardsUse() async throws {
        let (handle, path) = try await newProject()
        defer { try? FileManager.default.removeItem(atPath: path) }
        _ = try await service.enableAddon(handle: handle, id: "jwt-claims", config: jwtConfig, autosave: true)
        _ = try await service.upsertEndpoint(
            handle: handle,
            input: JSONDecoder().decode(
                EndpointUpsertInput.self, from: Data(#"{"id":"get-me","method":"GET","path":"/v1/me"}"#.utf8)),
            autosave: true)
        let match = RequestMatch(addons: ["jwt-claims": .object(["claims": .object(["sub": .string("u1")])])])
        try await service.upsertVariant(
            handle: handle, endpointID: "get-me", variant: ProjectVariant(name: "u1", status: 200, requestMatch: match),
            autosave: true)

        await #expect(throws: MoqServiceError.addonInUse(id: "jwt-claims", endpointIDs: ["get-me"])) {
            _ = try await service.disableAddon(handle: handle, id: "jwt-claims", autosave: true)
        }
        try await service.removeVariant(
            handle: handle,
            input: JSONDecoder().decode(
                RemoveVariantInput.self, from: Data(#"{"endpoint_id":"get-me","name":"u1"}"#.utf8)),
            autosave: true)
        let state = try await service.disableAddon(handle: handle, id: "jwt-claims", autosave: true)
        #expect(state.enabled.isEmpty)
        #expect(try reopened(path).manifest.addons == nil)
        #expect(try await service.disableAddon(handle: handle, id: "jwt-claims", autosave: true).enabled.isEmpty)
    }
}
