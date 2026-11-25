import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport

struct OpenAPIParameterConstraintTests {
    private func spec(_ parameters: String) -> String {
        """
        openapi: 3.0.3
        info:
          title: API
          version: "1.0"
        paths:
          /v1/items:
            get:
              parameters:
        \(parameters)
              responses:
                "200":
                  description: OK
        """
    }

    private func queryRules(_ parameters: String) throws -> [RuleMatcher] {
        try #require(OpenAPIImporter.parse(spec(parameters)).endpoints.first).queryParameters
    }

    @Test("enum, pattern and bounds become value rules; optional ones still allow omission")
    func constraintsBecomeRules() throws {
        let rules = try queryRules(
            """
                    - name: status
                      in: query
                      schema:
                        type: string
                        enum: [open, done, a.b]
                      example: open
                    - name: page
                      in: query
                      required: true
                      schema:
                        type: integer
                        minimum: 1
                        maximum: 100
                    - name: ratio
                      in: query
                      schema:
                        type: number
                        minimum: 0
                        exclusiveMinimum: true
                        maximum: 0.5
                    - name: code
                      in: query
                      schema:
                        type: string
                        pattern: "^[A-Z]{3}$"
            """)
        #expect(
            rules == [
                RuleMatcher(name: "code", match: "^[A-Z]{3}$", required: false, matchType: .matchesRegex),
                RuleMatcher(name: "page", match: "1", required: true, matchType: .gte),
                RuleMatcher(name: "page", match: "100", required: true, matchType: .lte),
                RuleMatcher(name: "ratio", match: "0", required: false, matchType: .gt),
                RuleMatcher(name: "ratio", match: "0.5", required: false, matchType: .lte),
                RuleMatcher(name: "status", match: #"^(?:open|done|a\.b)$"#, required: false, matchType: .matchesRegex),
            ])
    }

    @Test("An example alone is not a constraint")
    func examplesAddNoRule() throws {
        let rules = try queryRules(
            """
                    - name: sort
                      in: query
                      schema:
                        type: string
                      example: name
                    - name: q
                      in: query
                      required: true
                      schema:
                        type: string
                      example: shoes
            """)
        #expect(rules == [RuleMatcher(name: "q", required: true)])
    }

    @Test("Imported rules accept every legal value and refuse others")
    func rulesEvaluate() throws {
        let rules = try queryRules(
            """
                    - name: status
                      in: query
                      schema:
                        type: string
                        enum: [open, done]
                    - name: page
                      in: query
                      schema:
                        type: integer
                        minimum: 1
            """)
        func fails(_ query: [String: String]) -> Bool {
            rules.contains { RuleEvaluator.evaluate($0, actualValue: query[$0.name]) != nil }
        }
        #expect(!fails([:]))
        #expect(!fails(["status": "done", "page": "3"]))
        #expect(fails(["status": "closed"]))
        #expect(fails(["status": "done "]))
        #expect(fails(["page": "0"]))
    }

    @Test("Re-importing replaces query rules whose constraints changed")
    func reimportUpdatesChangedConstraints() throws {
        let first = try OpenAPIImporter.parse(
            spec(
                """
                        - name: status
                          in: query
                          schema:
                            type: string
                            example: open
                """))
        let project = ImportConverter.convert(first, selection: .all, name: "Test", path: "/tmp/test.moqproj")
        let stale = EndpointDocument(
            id: project.endpoints[0].id, method: "GET", path: "/v1/items",
            requestRules: RequestRules(queryParams: [RuleMatcher(name: "status", match: "open", matchType: .equalTo)]),
            variants: project.endpoints[0].variants)
        let existing = MoqProject(manifest: project.manifest, endpoints: [stale], projectPath: project.projectPath)

        let second = try OpenAPIImporter.parse(
            spec(
                """
                        - name: status
                          in: query
                          schema:
                            type: string
                            enum: [open, done]
                """))
        let merged = ImportConverter.merge(second, selection: .all, policy: ImportMergePolicy(), into: existing)
        #expect(
            merged.endpoints[0].requestRules?.queryParams == [
                RuleMatcher(name: "status", match: "^(?:open|done)$", required: false, matchType: .matchesRegex)
            ])
    }
}
