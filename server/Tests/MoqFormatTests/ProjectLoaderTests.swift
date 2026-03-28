import Foundation
import Testing
@testable import MoqCore
@testable import MoqFormat

@Suite("ProjectLoader")
struct ProjectLoaderTests {
    func fixturePath() throws -> String {
        let url = Bundle.module.url(forResource: "sample-app.moqproj", withExtension: nil, subdirectory: "Fixtures")!
        return url.path
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
        #expect(listUsers?.method == "GET")
        #expect(listUsers?.path == "/api/v1/users")
        #expect(listUsers?.tags == ["users", "core"])
        #expect(listUsers?.auth?.type == .bearer)
        #expect(listUsers?.auth?.verify == true)
        #expect(listUsers?.variants.count == 4)

        let success = listUsers?.variants.first { $0.name == "success" }
        #expect(success?.isDefault == true)
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
}
