import Foundation
import Testing
@testable import MoqCore
@testable import MoqFormat

@Suite("ProjectValidator")
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

    @Test("Detects duplicate endpoint IDs")
    func detectsDuplicateIds() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "pets"),
            sampleEndpoint(id: "pets", path: "/pets2"),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate endpoint id") })
    }

    @Test("Rejects reserved paths")
    func rejectsReservedPaths() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "health-mock", path: "/health"),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("reserved") })
    }

    @Test("Rejects multiple default variants")
    func rejectsMultipleDefaults() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "a", isDefault: true, status: 200),
                ProjectVariant(name: "b", isDefault: true, status: 500),
            ]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Only one variant") })
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
                ),
            ]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("both body and body_file") })
    }

    @Test("Rejects body_file not in fixtures/")
    func rejectsBodyFileOutsideFixtures() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "bad", status: 200, bodyFile: "other/data.json"),
            ]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("must start with \"fixtures/\"") })
    }

    @Test("Rejects path traversal in body_file")
    func rejectsPathTraversal() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "bad", status: 200, bodyFile: "fixtures/../../../etc/passwd"),
            ]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("path traversal") })
    }

    @Test("Rejects invalid endpoint ID format")
    func rejectsInvalidIdFormat() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "Invalid_Id"),
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
                ),
            ],
            projectPath: "/tmp/test.moqproj"
        )
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("header_name is required") })
    }

    @Test("Validates GraphQL endpoint requires operation")
    func graphQLRequiresOperation() {
        let project = makeProject(endpoints: [
            sampleEndpoint(id: "graphql-test", method: "POST", path: "/graphql"),
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
            ),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("at least one of") })
    }

    @Test("Rejects duplicate variant names")
    func rejectsDuplicateVariantNames() {
        let project = makeProject(endpoints: [
            sampleEndpoint(variants: [
                ProjectVariant(name: "default", status: 200),
                ProjectVariant(name: "default", status: 500),
            ]),
        ])
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("Duplicate variant name") })
    }

    @Test("Sample project passes validation")
    func sampleProjectPasses() throws {
        let loader = ProjectLoader()
        let path = Bundle.module.url(forResource: "sample-app.moqproj", withExtension: nil, subdirectory: "Fixtures")!.path
        let project = try loader.load(from: path)
        let errors = validator.validate(project).filter { $0.severity == .error }
        #expect(errors.isEmpty)
    }
}
