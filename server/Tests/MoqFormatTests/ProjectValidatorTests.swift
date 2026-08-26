import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

struct ProjectValidatorTests {
    let validator = ProjectValidator()

    func sampleManifest() -> ProjectManifest {
        ProjectManifest(
            version: "1",
            name: "Test",
            defaults: ProjectDefaults(
                delayMs: 0,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            )
        )
    }

    func sampleEndpoint(
        id: String = "test-endpoint",
        method: String = "GET",
        path: String = "/test",
        variants: [ProjectVariant] = [ProjectVariant(name: "default", status: 200)]
    ) -> EndpointDocument {
        EndpointDocument(id: id, method: method, path: path, variants: variants)
    }

    func makeProject(endpoints: [EndpointDocument]) -> MoqProject {
        MoqProject(manifest: sampleManifest(), endpoints: endpoints, projectPath: "/tmp/test.moqproj")
    }

    @Test("Valid project passes validation")
    func validProjectPasses() {
        let project = makeProject(endpoints: [sampleEndpoint()])
        let diagnostics = validator.validate(project)
        let errors = diagnostics.filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }

    @Test("Rejects unsupported manifest version")
    func rejectsUnsupportedManifestVersion() {
        let project = MoqProject(
            manifest: ProjectManifest(
                version: "2",
                name: "Test",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            endpoints: [sampleEndpoint()],
            projectPath: "/tmp/test.moqproj"
        )

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.file == "project.yml" && $0.field == "version" })
    }

    @Test("Rejects projects with no endpoints")
    func rejectsProjectsWithNoEndpoints() {
        let project = makeProject(endpoints: [])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("No endpoint files found") })
    }

    @Test("Detects duplicate endpoint IDs")
    func detectsDuplicateIds() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "pets"),
            sampleEndpoint(id: "pets", path: "/pets2"),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate endpoint id") })
    }

    @Test("Rejects duplicate endpoint reference names")
    func rejectsDuplicateEndpointReferenceNames() {
        let project = makeProject(endpoints: [
            EndpointDocument(
                id: "pets", referenceName: "petsApi", method: "GET", path: "/pets",
                variants: [ProjectVariant(name: "default", status: 200)]),
            EndpointDocument(
                id: "pets-2", referenceName: "petsApi", method: "GET", path: "/pets-2",
                variants: [ProjectVariant(name: "default", status: 200)]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate endpoint reference_name") })
    }

    @Test("Rejects reserved paths")
    func rejectsReservedPaths() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "health-mock", path: "/health"),
            sampleEndpoint(id: "admin-mock", path: "/_admin/custom"),
            sampleEndpoint(id: "auth-mock", path: "/_auth/custom"),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.filter { $0.message.contains("reserved") }.count == 3)
    }

    @Test("Rejects equivalent normalized REST routes")
    func rejectsEquivalentRoutes() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "user-by-id", path: "/users/{id}"),
            sampleEndpoint(id: "user-by-name", path: "//users/{name}/"),
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate REST route") })
    }

    @Test("Allows equivalent templates for different methods")
    func allowsEquivalentRoutesForDifferentMethods() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "get-user", method: "GET", path: "/users/{id}"),
            sampleEndpoint(id: "delete-user", method: "DELETE", path: "/users/{name}"),
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(!errors.contains { $0.message.contains("Duplicate REST route") })
    }

    @Test("Rejects multiple default variants")
    func rejectsMultipleDefaults() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "a", isDefault: true, status: 200),
                ProjectVariant(name: "b", isDefault: true, status: 500),
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Only one variant") })
    }

    @Test("Rejects invalid and duplicate variant reference names")
    func rejectsInvalidAndDuplicateVariantReferenceNames() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "default", referenceName: "bad name", status: 200),
                ProjectVariant(name: "error", referenceName: "bad_name", status: 500),
                ProjectVariant(name: "error-2", referenceName: "bad_name", status: 502),
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("must start with a letter or underscore") })
        #expect(errors.contains { $0.message.contains("Duplicate variant reference_name") })
    }

    @Test("Rejects empty variant request_match")
    func rejectsEmptyVariantRequestMatch() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "default", status: 200, requestMatch: RequestMatch())
            ])
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("request_match must define query, headers, or body_contains") })
    }

    @Test("Rejects blank query and header names in variant request_match")
    func rejectsBlankRequestMatchNames() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(
                    name: "default",
                    status: 200,
                    requestMatch: RequestMatch(query: ["": "pets"], headers: ["": "admin"])
                )
            ])
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("query names must not be blank") })
        #expect(errors.contains { $0.message.contains("header names must not be blank") })
    }

    @Test("Rejects body and body_file together")
    func rejectsBodyAndBodyFile() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(
                    name: "bad",
                    status: 200,
                    body: .string("hello"),
                    bodyFile: "fixtures/test.json"
                )
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("both body and body_file") })
    }

    @Test("Rejects body_file not in fixtures/")
    func rejectsBodyFileOutsideFixtures() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "bad", status: 200, bodyFile: "other/data.json")
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("must start with \"fixtures/\"") })
    }

    @Test("Rejects fixture symlinks that escape the project")
    func rejectsEscapingFixtureSymlink() throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("moqserver-fixture-test-\(UUID().uuidString)")
        let projectURL = root.appendingPathComponent("test.moqproj")
        let fixturesURL = projectURL.appendingPathComponent("fixtures")
        try FileManager.default.createDirectory(at: fixturesURL, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let outsideURL = root.appendingPathComponent("secret.json")
        try Data("secret".utf8).write(to: outsideURL)
        try FileManager.default.createSymbolicLink(
            at: fixturesURL.appendingPathComponent("secret.json"),
            withDestinationURL: outsideURL
        )
        let project = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                sampleEndpoint(variants: [
                    ProjectVariant(name: "default", status: 200, bodyFile: "fixtures/secret.json")
                ])
            ],
            projectPath: projectURL.path
        )

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("inside the project's fixtures directory") })
    }

    @Test("Rejects invalid response and network simulation values")
    func rejectsInvalidNumericValues() {
        let endpoint = EndpointDocument(
            id: "invalid-network",
            method: "GET",
            path: "/network",
            network: NetworkBehavior(latencyMs: -1, jitterMs: -2, packetLossPercent: 101),
            variants: [ProjectVariant(name: "default", status: 99, delayMs: -1)]
        )
        let project = makeProject(endpoints: [endpoint])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.field == "variants[0].status" })
        #expect(errors.contains { $0.field == "variants[0].delay_ms" })
        #expect(errors.contains { $0.field == "network.latency_ms" })
        #expect(errors.contains { $0.field == "network.jitter_ms" })
        #expect(errors.contains { $0.field == "network.packet_loss_percent" })
    }

    @Test("Rejects combined delay overflow")
    func rejectsDelayOverflow() {
        let manifest = ProjectManifest(
            version: "1",
            name: "Test",
            defaults: ProjectDefaults(
                delayMs: Int.max,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            )
        )
        let project = MoqProject(
            manifest: manifest,
            endpoints: [sampleEndpoint(variants: [ProjectVariant(name: "default", status: 200, delayMs: 1)])],
            projectPath: "/tmp/test.moqproj"
        )

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("integer range") })
    }

    @Test("Rejects path traversal in body_file")
    func rejectsPathTraversal() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "bad", status: 200, bodyFile: "fixtures/../../../etc/passwd")
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("path traversal") })
    }

    @Test("Rejects invalid endpoint ID format")
    func rejectsInvalidIdFormat() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "Invalid_Id")
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("lowercase alphanumeric") })
    }

    @Test("Requires header_name for api-key auth type")
    func requiresHeaderNameForApiKey() {
        let project = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                EndpointDocument(
                    id: "api-test",
                    method: "GET",
                    path: "/test",
                    auth: ProjectAuthConfig(type: .apiKey, verify: true),
                    variants: [ProjectVariant(name: "default", status: 200)]
                )
            ],
            projectPath: "/tmp/test.moqproj"
        )
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("header_name is required") })
    }

    @Test("Validates GraphQL endpoint requires operation")
    func graphQLRequiresOperation() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "graphql-test", method: "POST", path: "/graphql")
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("must define an operation") })
    }

    @Test("Validates GraphQL operation needs name or document")
    func graphQLNeedsNameOrDocument() {
        let project = makeProject(endpoints: [
            EndpointDocument(
                id: "graphql-test",
                method: "POST",
                path: "/graphql",
                operation: EndpointOperation(type: .query),
                variants: [ProjectVariant(name: "default", status: 200)]
            )
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("at least one of") })
    }

    @Test("Warns when endpoint operation is used outside /graphql")
    func graphQLOperationOutsideGraphQLPathWarns() {
        let project = makeProject(endpoints: [
            EndpointDocument(
                id: "users-query",
                method: "POST",
                path: "/users/query",
                operation: EndpointOperation(type: .query, name: "UsersQuery"),
                variants: [ProjectVariant(name: "default", status: 200)]
            )
        ])

        let warnings = validator.validate(project).filter { $0.severity == .warning }
        #expect(warnings.contains { $0.message.contains("path is not /graphql") })
    }

    @Test("Rejects GraphQL document that is blank after trimming")
    func graphQLDocumentMustNotBeBlank() {
        let project = makeProject(endpoints: [
            EndpointDocument(
                id: "graphql-test",
                method: "POST",
                path: "/graphql",
                operation: EndpointOperation(type: .query, document: "  \n  "),
                variants: [ProjectVariant(name: "default", status: 200)]
            )
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("non-empty after normalization") })
    }

    @Test("Rejects invalid HTTP method and non-absolute path")
    func rejectsInvalidMethodAndPath() {
        let project = makeProject(endpoints: [
            sampleEndpoint(method: "TRACE", path: "pets")
        ])

        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Invalid HTTP method") })
        #expect(errors.contains { $0.message.contains("Path must start with") })
    }

    @Test("Rejects duplicate variant names")
    func rejectsDuplicateVariantNames() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "default", status: 200),
                ProjectVariant(name: "default", status: 500),
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate variant name") })
    }

    @Test("Rejects call_count below 1")
    func rejectsInvalidCallCount() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "default", status: 200, callCount: 0)
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.code == .invalidCallCount })
    }

    @Test("Rejects duplicate call_count on the same endpoint")
    func rejectsDuplicateCallCount() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "first", status: 200, callCount: 1),
                ProjectVariant(name: "also-first", status: 200, callCount: 1),
            ])
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.code == .duplicateCallCount })
    }

    @Test("Warns when strict_call_count is set with no call_count-tagged variant")
    func warnsStrictCallCountWithoutCallCount() {
        let endpoint = EndpointDocument(
            id: "test-endpoint", method: "GET", path: "/test",
            variants: [ProjectVariant(name: "default", status: 200)],
            strictCallCount: true
        )
        let project = makeProject(endpoints: [endpoint])
        let warnings = validator.validate(project).filter { $0.severity == .warning }
        #expect(warnings.contains { $0.code == .strictCallCountWithoutCallCount })
    }

    @Test("Sample project passes validation")
    func sampleProjectPasses() throws {
        let loader = ProjectLoader()
        let path = Bundle.module.url(forResource: "sample-app.moqproj", withExtension: nil, subdirectory: "Fixtures")!
            .path
        let project = try loader.load(from: path)
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }
}
