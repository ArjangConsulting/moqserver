import Foundation
import Testing
@testable import MoqCore

struct CoreValueTypesTests {
    @Test("ValidationDiagnostic description includes optional context")
    func validationDiagnosticDescription() {
        let diagnostic = ValidationDiagnostic(
            severity: .warning,
            message: "Something happened",
            file: "project.yml",
            field: "manifest.version"
        )

        #expect(diagnostic.description == "[warning] project.yml (manifest.version) Something happened")
    }

    @Test("ValidationDiagnostic description omits missing file and field")
    func validationDiagnosticDescriptionWithoutContext() {
        let diagnostic = ValidationDiagnostic(severity: .error, message: "Broken")
        #expect(diagnostic.description == "[error] Broken")
    }

    @Test("HTTP method normalizes raw value and describes itself")
    func httpMethodValueUppercases() {
        let method = HTTPMethodValue(rawValue: "post")
        #expect(method.rawValue == "POST")
        #expect(method.description == "POST")
        #expect(HTTPMethodValue.trace.description == "TRACE")
    }

    @Test("HTTP status code describes numeric code")
    func httpStatusCodeDescription() {
        let status = HTTPStatusCode(code: 418)
        #expect(status.description == "418")
        #expect(HTTPStatusCode.ok.description == "200")
    }

    @Test("Endpoint merges required rules and falls back to default variant")
    func endpointMergesRequiredRulesAndSelectsVariants() {
        let defaultVariant = ResponseVariant(name: "Success Response", referenceName: "success-response", isDefault: true)
        let alternateVariant = ResponseVariant(name: "Error")
        let pageRule = RuleMatcher(name: "page", match: "1", matchType: .equalTo)
        let endpoint = Endpoint(
            key: EndpointKey(method: .get, path: "/pets"),
            authRequirement: .none,
            variants: [defaultVariant, alternateVariant],
            queryParamRules: [pageRule],
            headerRules: [],
            requiredQueryParameters: ["page", "limit"],
            requiredHeaders: ["X-Trace-Id"]
        )

        #expect(endpoint.queryParamRules.contains(pageRule))
        #expect(endpoint.requiredQueryParameters == ["limit"])
        #expect(endpoint.requiredHeaders == ["X-Trace-Id"])
        #expect(endpoint.defaultVariant?.name == "Success Response")
        #expect(endpoint.variant(named: "success-response")?.name == "Success Response")
        #expect(endpoint.variant(named: "missing")?.name == "Success Response")
        let noName: String? = nil
        #expect(endpoint.variant(named: noName)?.name == "Success Response")
    }
}
