import Foundation
import Testing
@testable import MoqCore
@testable import MoqFormat

struct ProjectLoaderTests {
    func fixturePath() throws -> String {
        let url = Bundle.module.url(forResource: "sample-app.moqproj", withExtension: nil, subdirectory: "Fixtures")!
        return url.path
    }

    func makeTempProject(
        manifestYAML: String? = nil,
        endpointFiles: [String: String] = [:],
        createEndpointsDirectory: Bool = true
    ) throws -> String {
        let root = (NSTemporaryDirectory() as NSString).appendingPathComponent("loader-test-\(UUID().uuidString).moqproj")
        try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)

        if let manifestYAML {
            let manifestPath = (root as NSString).appendingPathComponent("project.yml")
            try manifestYAML.write(toFile: manifestPath, atomically: true, encoding: .utf8)
        }

        if createEndpointsDirectory {
            let endpointsPath = (root as NSString).appendingPathComponent("endpoints")
            try FileManager.default.createDirectory(atPath: endpointsPath, withIntermediateDirectories: true)
            for (name, yaml) in endpointFiles {
                let filePath = (endpointsPath as NSString).appendingPathComponent(name)
                try yaml.write(toFile: filePath, atomically: true, encoding: .utf8)
            }
        }

        return root
    }

    var validManifestYAML: String {
        """
        version: "1"
        name: "Temp"
        defaults:
          delay_ms: 0
          auth:
            type: none
            verify: false
            header_name: null
          network:
            latency_ms: 0
            jitter_ms: 0
            packet_loss_percent: 0
        """
    }

    func endpointYAML(id: String, method: String = "GET", path: String = "/\(UUID().uuidString.lowercased())") -> String {
        """
        id: \(id)
        alias: "\(id)"
        reference_name: "\(id.replacingOccurrences(of: "-", with: ""))"
        method: \(method)
        path: \(path)
        variants:
          - name: default
            reference_name: "default"
            status: 200
        """
    }

    @Test("Loads sample project successfully")
    func loadsSampleProject() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        #expect(project.manifest.version == "1")
        #expect(project.manifest.name == "Sample App API Mock")
        #expect(project.manifest.defaults.delayMs == 0)
        #expect(project.manifest.defaults.auth.type == .none)
        #expect(project.manifest.defaults.auth.verify == false)
        #expect(project.manifest.defaults.network.latencyMs == 0)
        #expect(project.endpoints.count == 3)
    }

    @Test("Loads manifest global rules")
    func loadsGlobalRules() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        #expect(project.manifest.globalRules?.verifyCookies == false)
        #expect(project.manifest.globalRules?.requiredHeaders?.isEmpty == true)
    }

    @Test("Loads REST endpoint with auth and variants")
    func loadsRestEndpoint() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let listUsers = project.endpoints.first { $0.id == "list-users" }
        #expect(listUsers != nil)
        #expect(listUsers?.description == nil)
        #expect(listUsers?.referenceName == "listUsers")
        #expect(listUsers?.method == "GET")
        #expect(listUsers?.path == "/api/v1/users")
        #expect(listUsers?.tags == ["users", "core"])
        #expect(listUsers?.auth?.type == .bearer)
        #expect(listUsers?.auth?.verify == true)
        #expect(listUsers?.variants.count == 4)

        let success = listUsers?.variants.first { $0.name == "success" }
        #expect(success?.isDefault == true)
        #expect(success?.referenceName == "success")
        #expect(success?.status == 200)
        #expect(success?.bodyFile == "fixtures/users-list.json")
        #expect(success?.delayMs == 50)
    }

    @Test("Loads endpoint request rules")
    func loadsRequestRules() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let listUsers = project.endpoints.first { $0.id == "list-users" }
        let rules = listUsers?.requestRules
        #expect(rules?.verifyCookies == true)
        #expect(rules?.headers?.count == 1)
        #expect(rules?.headers?.first?.name == "Accept")
        #expect(rules?.headers?.first?.match == "application/json")
        #expect(rules?.headers?.first?.required == true)
        #expect(rules?.headers?.first?.matchType == .equalTo)
        #expect(rules?.cookies?.first?.name == "session_id")
        #expect(rules?.cookies?.first?.matchType == .notEmpty)
    }

    @Test("Loads GraphQL endpoint with named operation")
    func loadsGraphQLNamedOp() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let profile = project.endpoints.first { $0.id == "get-user-profile" }
        #expect(profile != nil)
        #expect(profile?.method == "POST")
        #expect(profile?.path == "/graphql")
        #expect(profile?.operation?.type == .query)
        #expect(profile?.operation?.name == "GetUserProfile")
    }

    @Test("Loads GraphQL endpoint with document")
    func loadsGraphQLDocumentOp() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let current = project.endpoints.first { $0.id == "current-user" }
        #expect(current != nil)
        #expect(current?.operation?.type == .query)
        #expect(current?.operation?.document?.contains("currentUser") == true)
    }

    @Test("Loads inline body as AnyCodableValue")
    func loadsInlineBody() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let listUsers = project.endpoints.first { $0.id == "list-users" }
        let emptyVariant = listUsers?.variants.first { $0.name == "empty" }
        #expect(emptyVariant?.body != nil)
        #expect(emptyVariant?.bodyFile == nil)
    }

    @Test("Loads network behavior")
    func loadsNetworkBehavior() throws {
        let loader = ProjectLoader()
        let project = try loader.load(from: try fixturePath())

        let listUsers = project.endpoints.first { $0.id == "list-users" }
        #expect(listUsers?.network?.latencyMs == 100)
        #expect(listUsers?.network?.jitterMs == 20)
    }

    @Test("Rejects nonexistent path")
    func rejectsNonexistentPath() {
        let loader = ProjectLoader()
        #expect(throws: ProjectLoadError.self) {
            try loader.load(from: "/nonexistent/path.moqproj")
        }
    }

    @Test("Rejects paths that are not directories")
    func rejectsNonDirectoryPath() throws {
        let filePath = (NSTemporaryDirectory() as NSString).appendingPathComponent("loader-file-\(UUID().uuidString).txt")
        try "test".write(toFile: filePath, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: filePath) }

        do {
            _ = try ProjectLoader().load(from: filePath)
            Issue.record("Expected notADirectory error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("Not a directory"))
        }
    }

    @Test("Rejects missing manifest file")
    func rejectsMissingManifest() throws {
        let projectPath = try makeTempProject(manifestYAML: nil, endpointFiles: ["a.yml": endpointYAML(id: "a")])
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        do {
            _ = try ProjectLoader().load(from: projectPath)
            Issue.record("Expected missingManifest error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("Missing project.yml"))
        }
    }

    @Test("Rejects invalid manifest YAML")
    func rejectsInvalidManifest() throws {
        let projectPath = try makeTempProject(manifestYAML: "defaults: [", endpointFiles: ["a.yml": endpointYAML(id: "a")])
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        do {
            _ = try ProjectLoader().load(from: projectPath)
            Issue.record("Expected invalidManifest error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("Invalid project.yml"))
        }
    }

    @Test("Rejects missing endpoints directory")
    func rejectsMissingEndpointsDirectory() throws {
        let projectPath = try makeTempProject(manifestYAML: validManifestYAML, createEndpointsDirectory: false)
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        do {
            _ = try ProjectLoader().load(from: projectPath)
            Issue.record("Expected missingEndpointsDirectory error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("Missing endpoints/ directory"))
        }
    }

    @Test("Rejects empty endpoints directory")
    func rejectsEmptyEndpointsDirectory() throws {
        let projectPath = try makeTempProject(manifestYAML: validManifestYAML)
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        do {
            _ = try ProjectLoader().load(from: projectPath)
            Issue.record("Expected noEndpointFiles error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("No endpoint files found"))
        }
    }

    @Test("Rejects invalid endpoint YAML")
    func rejectsInvalidEndpointYAML() throws {
        let projectPath = try makeTempProject(manifestYAML: validManifestYAML, endpointFiles: ["broken.yml": "variants: ["])
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        do {
            _ = try ProjectLoader().load(from: projectPath)
            Issue.record("Expected invalidEndpointFile error")
        } catch let error as ProjectLoadError {
            #expect(error.description.contains("Invalid endpoint file"))
        }
    }

    @Test("Loads yml and yaml endpoint files in sorted filename order")
    func loadsMixedExtensionsInSortedOrder() throws {
        let projectPath = try makeTempProject(
            manifestYAML: validManifestYAML,
            endpointFiles: [
                "b.yml": endpointYAML(id: "b", path: "/b"),
                "a.yaml": endpointYAML(id: "a", path: "/a"),
            ]
        )
        defer { try? FileManager.default.removeItem(atPath: projectPath) }

        let project = try ProjectLoader().load(from: projectPath)
        #expect(project.endpoints.map(\.id) == ["a", "b"])
    }
}
