import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

@Suite("request_rules: regex validation and body rules")
struct RequestRulesFormatTests {
    private func project(_ endpoint: EndpointDocument, globalRules: GlobalRules? = nil) -> MoqProject {
        MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: "Rules",
                defaults: ProjectDefaults(
                    delayMs: 0, auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior()),
                globalRules: globalRules
            ),
            endpoints: [endpoint],
            projectPath: "/tmp/rules.moqproj"
        )
    }

    private func endpoint(rules: RequestRules?) -> EndpointDocument {
        EndpointDocument(
            id: "create-order", method: "POST", path: "/orders", requestRules: rules,
            variants: [ProjectVariant(name: "created", isDefault: true, status: 201)])
    }

    @Test("An invalid matches_regex pattern is a load-time error naming the field")
    func invalidRegexRejected() {
        let rules = RequestRules(
            headers: [RuleMatcher(name: "X-Trace", match: "^(unclosed", matchType: .matchesRegex)],
            queryParams: [RuleMatcher(name: "v", match: "^[0-9]+$", matchType: .matchesRegex)])
        let diagnostics = ProjectValidator().validate(project(endpoint(rules: rules)))
        let regexErrors = diagnostics.filter { $0.code == .invalidRegex }
        #expect(regexErrors.count == 1)
        #expect(regexErrors.first?.field == "request_rules.headers[0].match")
        #expect(regexErrors.first?.severity == .error)
    }

    @Test("A matches_regex rule without a pattern and a bad global rule are both reported")
    func missingPatternAndGlobalRule() {
        let rules = RequestRules(cookies: [RuleMatcher(name: "sid", matchType: .matchesRegex)])
        let global = GlobalRules(requiredHeaders: [RuleMatcher(name: "X-App", match: "[", matchType: .matchesRegex)])
        let diagnostics = ProjectValidator().validate(project(endpoint(rules: rules), globalRules: global))
        let fields = diagnostics.filter { $0.code == .invalidRegex }.map(\.field)
        #expect(fields.contains("request_rules.cookies[0].match"))
        #expect(fields.contains("global_rules.required_headers[0].match"))
    }

    @Test("A comparison with no match value warns only when it is not required")
    func comparisonWithoutValueWarns() {
        let rules = RequestRules(
            headers: [
                RuleMatcher(name: "X-Api-Version", required: true, matchType: .equalTo),
                RuleMatcher(name: "X-Trace", required: true, matchType: .require),
                RuleMatcher(name: "X-Empty", match: "", matchType: .equalTo),
            ],
            cookies: [RuleMatcher(name: "sid", matchType: .gte)])
        let diagnostics = ProjectValidator().validate(project(endpoint(rules: rules)))
        let warnings = diagnostics.filter { $0.code == .ruleWithoutMatchValue }
        #expect(warnings.map(\.field) == ["request_rules.cookies[0].match"])
        #expect(warnings.allSatisfy { $0.severity == .warning && $0.endpointID == "create-order" })
    }

    @Test("Blank rule names and blank accepted content types are errors naming the field")
    func blankEntriesRejected() {
        let rules = RequestRules(
            headers: [RuleMatcher(name: " ", required: true, matchType: .require)],
            acceptedContentTypes: ["application/json", ""])
        let global = GlobalRules(requiredHeaders: [RuleMatcher(name: "", required: true)])
        let diagnostics = ProjectValidator().validate(project(endpoint(rules: rules), globalRules: global))
        #expect(
            diagnostics.filter { $0.code == .blankRuleName }.compactMap(\.field).sorted() == [
                "global_rules.required_headers[0].name", "request_rules.headers[0].name",
            ])
        #expect(
            diagnostics.filter { $0.code == .blankAcceptedContentType }.map(\.field)
                == ["request_rules.accepted_content_types[1]"])
        #expect(diagnostics.filter { $0.code == .blankRuleName }.allSatisfy { $0.severity == .error })
    }

    @Test("requires_body and accepted_content_types reach the runtime endpoint")
    func converterCarriesBodyRules() throws {
        let rules = RequestRules(requiresBody: true, acceptedContentTypes: ["application/json"])
        let runtime = try ProjectToRuntimeConverter.convertEndpoint(
            endpoint(rules: rules), defaults: project(endpoint(rules: rules)).manifest.defaults,
            projectPath: "/tmp/rules.moqproj")
        #expect(runtime.requiresBody)
        #expect(runtime.acceptedContentTypes == ["application/json"])
    }

    @Test("requires_body and accepted_content_types round-trip through the writer and loader")
    func roundTrip() throws {
        let rules = RequestRules(requiresBody: true, acceptedContentTypes: ["application/json", "image/*"])
        let outputPath = (NSTemporaryDirectory() as NSString).appendingPathComponent(
            "body-rules-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try ProjectWriter().write(project(endpoint(rules: rules)), to: outputPath)
        let yaml = try String(
            contentsOfFile: (outputPath as NSString).appendingPathComponent("endpoints/create-order.yml"),
            encoding: .utf8)
        #expect(yaml.contains("requires_body: true"))
        #expect(yaml.contains("accepted_content_types:"))

        let reloaded = try ProjectLoader().load(from: outputPath)
        #expect(reloaded.endpoints.first?.requestRules == rules)
    }
}
