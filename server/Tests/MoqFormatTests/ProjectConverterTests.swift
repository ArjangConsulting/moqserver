import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

struct ProjectConverterTests {
    @Test("Converts sample project to runtime endpoints")
    func convertsSampleProject() throws {
        let loader = ProjectLoader()
        let path = Bundle.module.url(
            forResource: "sample-app.moqproj",
            withExtension: nil,
            subdirectory: "Fixtures"
        )!.path
        let project = try loader.load(from: path)

        let endpoints = try ProjectToRuntimeConverter.convert(project)

        #expect(endpoints.count == 3)

        let listUsers = endpoints.first { $0.key.path == "/api/v1/users" }
        #expect(listUsers != nil)
        #expect(listUsers?.key.method == .get)
        #expect(listUsers?.authRequirement == .bearer)
        #expect(listUsers?.variants.count == 4)
        #expect(listUsers?.headerRules.first?.name == "Accept")
        #expect(listUsers?.headerRules.first?.matchType == .equalTo)
        #expect(listUsers?.cookieRules.first?.name == "session_id")
        #expect(listUsers?.verifyCookies == true)
        #expect(listUsers?.network?.latencyMs == 100)
        #expect(listUsers?.network?.jitterMs == 20)
        #expect(listUsers?.defaultVariant?.name == "success")
        #expect(listUsers?.variant(named: "success")?.referenceName == "success")
    }

    @Test("Honors explicit default variant instead of first variant")
    func honorsExplicitDefaultVariant() throws {
        let endpoint = EndpointDocument(
            id: "pets",
            referenceName: "pets",
            method: "GET",
            path: "/pets",
            variants: [
                ProjectVariant(name: "first", referenceName: "first", status: 200),
                ProjectVariant(name: "second", referenceName: "second", isDefault: true, status: 201),
            ]
        )
        let runtime = try ProjectToRuntimeConverter.convertEndpoint(
            endpoint,
            defaults: ProjectDefaults(
                delayMs: 0,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            ),
            projectPath: "/tmp"
        )

        #expect(runtime.defaultVariant?.name == "second")
    }

    @Test("Converts auth with verify=false to .none")
    func authVerifyFalseIsNone() {
        let auth = ProjectAuthConfig(type: .bearer, verify: false)
        let result = ProjectToRuntimeConverter.convertAuth(auth)
        #expect(result == .none)
    }

    @Test("Converts auth with verify=true to correct requirement")
    func authVerifyTrueConverts() {
        let bearer = ProjectToRuntimeConverter.convertAuth(
            ProjectAuthConfig(type: .bearer, verify: true)
        )
        #expect(bearer == .bearer)

        let basic = ProjectToRuntimeConverter.convertAuth(
            ProjectAuthConfig(type: .basic, verify: true)
        )
        #expect(basic == .basic)

        let apiKey = ProjectToRuntimeConverter.convertAuth(
            ProjectAuthConfig(type: .apiKey, verify: true, headerName: "X-Key")
        )
        #expect(apiKey == .apiKey(headerName: "X-Key"))
    }

    @Test("Resolves body_file to Data")
    func resolvesBodyFile() throws {
        let loader = ProjectLoader()
        let path = Bundle.module.url(
            forResource: "sample-app.moqproj",
            withExtension: nil,
            subdirectory: "Fixtures"
        )!.path
        let project = try loader.load(from: path)

        let endpoints = try ProjectToRuntimeConverter.convert(project)
        let listUsers = endpoints.first { $0.key.path == "/api/v1/users" }
        let success = listUsers?.variant(named: "success")
        #expect(success?.body != nil)

        // Should contain the fixture data
        if let body = success?.body, let json = String(data: body, encoding: .utf8) {
            #expect(json.contains("Test User"))
        }
    }

    @Test("Converts inline body to JSON Data")
    func convertsInlineBody() throws {
        let loader = ProjectLoader()
        let path = Bundle.module.url(
            forResource: "sample-app.moqproj",
            withExtension: nil,
            subdirectory: "Fixtures"
        )!.path
        let project = try loader.load(from: path)

        let endpoints = try ProjectToRuntimeConverter.convert(project)
        let listUsers = endpoints.first { $0.key.path == "/api/v1/users" }
        let empty = listUsers?.variant(named: "empty")
        #expect(empty?.body != nil)
    }

    @Test("Calculates delay from variant + defaults")
    func calculatesDelay() throws {
        let defaults = ProjectDefaults(
            delayMs: 100,
            auth: ProjectAuthConfig(type: .none, verify: false),
            network: NetworkBehavior()
        )
        let variant = ProjectVariant(name: "test", status: 200, delayMs: 50)

        let result = try ProjectToRuntimeConverter.convertVariant(
            variant,
            defaults: defaults,
            projectPath: "/tmp"
        )
        // 100 + 50 = 150ms = 0.15s
        #expect(result.delay == 0.15)
    }

    @Test("Converts variant request_match into runtime requestMatch")
    func convertsVariantRequestMatch() throws {
        let variant = ProjectVariant(
            name: "matched",
            referenceName: "matched",
            status: 200,
            requestMatch: RequestMatch(
                query: ["type": "active"],
                headers: ["X-Role": "admin"],
                bodyContains: "currentUser"
            )
        )

        let result = try ProjectToRuntimeConverter.convertVariant(
            variant,
            defaults: ProjectDefaults(
                delayMs: 0,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            ),
            projectPath: "/tmp"
        )

        #expect(result.requestMatch?.query == ["type": "active"])
        #expect(result.requestMatch?.headers == ["X-Role": "admin"])
        #expect(result.requestMatch?.bodyContains == "currentUser")
    }
}
