import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

struct ProjectCredentialNamesTests {
    private func project(
        redact: ProjectRedaction? = nil, addons: [String: AnyCodableValue]? = nil,
        endpointAuth: ProjectAuthConfig? = nil
    ) -> MoqProject {
        let manifest = ProjectManifest(
            name: "Redact",
            defaults: ProjectDefaults(
                auth: ProjectAuthConfig(type: .apiKey, verify: true, headerName: "X-App-Key"),
                network: NetworkBehavior()),
            addons: addons, redact: redact)
        let endpoint = EndpointDocument(
            id: "get-items", method: "GET", path: "/v1/items", auth: endpointAuth,
            variants: [ProjectVariant(name: "ok", status: 200)])
        return MoqProject(manifest: manifest, endpoints: [endpoint], projectPath: "/tmp/redact.moqproj")
    }

    @Test("A bundle's redact: lists, declared auth headers and add-on headers are credentials")
    func bundleNames() {
        let names = CredentialNames.forProject(
            project(
                redact: ProjectRedaction(
                    headers: ["X-Session-Ref"], queryParameters: ["sig"],
                    except: .init(headers: ["X-Id-Token"], queryParameters: ["token"])),
                addons: ["jwt-claims": .object(["verify_signature": .bool(false), "header": .string("X-Id-Token")])],
                endpointAuth: ProjectAuthConfig(type: .apiKey, verify: true, headerName: "X-Device-Key")),
            extraHeaders: ["X-Flag-Header"], exceptQueryParameters: ["password"])
        #expect(names.isCredentialHeader("X-Session-Ref"))
        #expect(names.isCredentialHeader("X-App-Key"))
        #expect(names.isCredentialHeader("X-Device-Key"))
        #expect(names.isCredentialHeader("X-Flag-Header"))
        // The add-on reads its token from X-Id-Token, so that wins over the exemption.
        #expect(names.isCredentialHeader("X-Id-Token"))
        #expect(names.isCredentialQueryParameter("sig"))
        #expect(!names.isCredentialQueryParameter("token"))
        #expect(!names.isCredentialQueryParameter("password"))
    }

    @Test("redact: round-trips through the writer and loader")
    func roundTrip() throws {
        let path = (NSTemporaryDirectory() as NSString).appendingPathComponent("redact-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: path) }
        let redact = ProjectRedaction(
            headers: ["X-Session-Ref"], queryParameters: ["sig"], except: .init(queryParameters: ["token"]))
        var original = project(redact: redact)
        original = MoqProject(manifest: original.manifest, endpoints: original.endpoints, projectPath: path)
        try ProjectWriter().write(original, to: path)
        #expect(try ProjectLoader().load(from: path).manifest.redact == redact)
    }

    @Test("Blank redact names and excepting a protected header are errors")
    func validation() {
        let diagnostics = ProjectValidator().validate(
            project(redact: ProjectRedaction(headers: [" "], except: .init(headers: ["cookie", "X-Id-Token"]))))
        #expect(diagnostics.filter { $0.code == .blankRedactName }.compactMap(\.field) == ["redact.headers[0]"])
        #expect(
            diagnostics.filter { $0.code == .protectedRedactException }.compactMap(\.field)
                == ["redact.except.headers[0]"])
    }
}
