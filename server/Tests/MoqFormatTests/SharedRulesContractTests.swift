import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

/// Pins the format constants that Swift and Kotlin must agree on. Swift now derives these from
/// `MoqFormatRules` (`server/Sources/MoqCore/Project/MoqFormatRules.swift`); Kotlin still
/// duplicates them by hand in `ProjectValidator.kt` until Studio delegates to the Swift core (see
/// the "Swift format core + MCP server" plan). The Kotlin half of this contract lives at
/// `studio/studio-project-format/src/jvmTest/kotlin/com/moqserver/studio/projectformat/SharedRulesContractTest.kt`.
///
/// Changing a value here without updating the Kotlin counterpart reintroduces the exact class of
/// drift this test exists to catch (see the reserved-path divergence fixed alongside this test).
struct SharedRulesContractTests {
    let validator = ProjectValidator()

    func sampleManifest(version: String = "1") -> ProjectManifest {
        ProjectManifest(
            version: version,
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

    func makeProject(version: String = "1", endpoints: [EndpointDocument]) -> MoqProject {
        MoqProject(manifest: sampleManifest(version: version), endpoints: endpoints, projectPath: "/tmp/test.moqproj")
    }

    // MARK: - Format version

    @Test("Format version is \"1\"")
    func formatVersion() {
        let errors = validator.validate(makeProject(version: "1", endpoints: [sampleEndpoint()]))
            .filter { $0.severity == .error }
        #expect(!errors.contains { $0.field == "version" })
    }

    // MARK: - Endpoint id pattern: ^[a-z0-9][a-z0-9-]*$

    @Test(
        "Endpoint id pattern accepts lowercase-alphanumeric-with-hyphens",
        arguments: ["a", "a1", "list-users", "user-2fa-token"]
    )
    func idPatternAccepts(id: String) {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(id: id)]))
            .filter { $0.severity == .error }
        #expect(!errors.contains { $0.field == "id" })
    }

    @Test(
        "Endpoint id pattern rejects uppercase, underscores, leading hyphen, camelCase",
        arguments: ["Users", "list_users", "-users", "listUsers", ""]
    )
    func idPatternRejects(id: String) {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(id: id)]))
            .filter { $0.severity == .error }
        #expect(errors.contains { $0.field == "id" })
    }

    // MARK: - Supported HTTP methods

    @Test(
        "Supported HTTP methods",
        arguments: ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]
    )
    func supportedMethodsAccepted(method: String) {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(method: method)]))
            .filter { $0.severity == .error }
        #expect(!errors.contains { $0.field == "method" })
    }

    @Test(
        "TRACE and CONNECT are not supported methods",
        arguments: ["TRACE", "CONNECT"]
    )
    func unsupportedMethodsRejected(method: String) {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(method: method)]))
            .filter { $0.severity == .error }
        #expect(errors.contains { $0.field == "method" })
    }

    // MARK: - Reserved paths

    @Test(
        "Reserved paths are rejected",
        arguments: ["/health", "/_admin", "/_admin/endpoints", "/_auth", "/_auth/token"]
    )
    func reservedPathsRejected(path: String) {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(path: path)]))
            .filter { $0.severity == .error }
        #expect(errors.contains { $0.message.contains("reserved") })
    }

    @Test("The old double-underscore admin path is not reserved")
    func doubleUnderscoreAdminPathIsNotReserved() {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(path: "/__admin/endpoints")]))
            .filter { $0.severity == .error }
        #expect(!errors.contains { $0.message.contains("reserved") })
    }

    // MARK: - MatchType wire values

    @Test("MatchType wire values")
    func matchTypeWireValues() {
        let expected = [
            "require", "equal_to", "not_equal_to", "contains", "not_contains",
            "begins_with", "ends_with", "matches_regex", "is_empty", "not_empty",
            "gt", "gte", "lt", "lte",
        ]
        #expect(MatchType.allCases.map(\.rawValue).sorted() == expected.sorted())
    }

    // MARK: - AuthType wire values

    @Test("AuthType wire values")
    func authTypeWireValues() {
        let expected = ["none", "bearer", "basic", "api-key", "header"]
        #expect(ProjectAuthConfig.AuthType.allCases.map(\.rawValue).sorted() == expected.sorted())
    }

    // MARK: - MoqFormatRules is the actual source ProjectValidator consults

    @Test("MoqFormatRules.formatVersion matches the validator's accepted version")
    func moqFormatRulesFormatVersion() {
        #expect(MoqFormatRules.formatVersion == "1")
    }

    @Test("MoqFormatRules.supportedMethods matches the validator's accepted methods")
    func moqFormatRulesSupportedMethods() {
        #expect(
            MoqFormatRules.supportedMethods == ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"])
    }

    @Test("MoqFormatRules.isReservedPath matches the validator's reserved-path rule")
    func moqFormatRulesReservedPaths() {
        for path in ["/health", "/_admin", "/_admin/endpoints", "/_auth", "/_auth/token"] {
            #expect(MoqFormatRules.isReservedPath(path), "expected \(path) to be reserved")
        }
        for path in ["/__admin/endpoints", "/users", "/graphql"] {
            #expect(!MoqFormatRules.isReservedPath(path), "expected \(path) to not be reserved")
        }
    }

    // MARK: - Diagnostic codes are actually attached, not just messages

    @Test("Reserved-path diagnostics carry the E_RESERVED_PATH code and endpoint id")
    func reservedPathDiagnosticCode() {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(id: "bad", path: "/_admin")]))
            .filter { $0.severity == .error }
        let diagnostic = errors.first { $0.code == .reservedPath }
        #expect(diagnostic != nil)
        #expect(diagnostic?.endpointID == "bad")
    }

    @Test("Invalid-method diagnostics carry the E_INVALID_METHOD code")
    func invalidMethodDiagnosticCode() {
        let errors = validator.validate(makeProject(endpoints: [sampleEndpoint(method: "TRACE")]))
            .filter { $0.severity == .error }
        #expect(errors.contains { $0.code == .invalidMethod })
    }

    @Test("Unsupported version diagnostics carry the E_UNSUPPORTED_VERSION code")
    func unsupportedVersionDiagnosticCode() {
        let errors = validator.validate(makeProject(version: "2", endpoints: [sampleEndpoint()]))
            .filter { $0.severity == .error }
        #expect(errors.contains { $0.code == .unsupportedVersion })
    }
}
