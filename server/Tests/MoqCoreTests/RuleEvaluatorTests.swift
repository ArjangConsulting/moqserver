import Foundation
import Testing

@testable import MoqCore

struct RuleEvaluatorTests {
    @Test("Evaluates all supported match types")
    func allMatchTypes() {
        #expect(RuleEvaluator.evaluate(.init(name: "a", required: true, matchType: .require), actualValue: "x") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "x", matchType: .equalTo), actualValue: "x") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "y", matchType: .notEqualTo), actualValue: "x") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "bc", matchType: .contains), actualValue: "abcd") == nil)
        #expect(
            RuleEvaluator.evaluate(.init(name: "a", match: "zz", matchType: .notContains), actualValue: "abcd") == nil)
        #expect(
            RuleEvaluator.evaluate(.init(name: "a", match: "ab", matchType: .beginsWith), actualValue: "abcd") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "cd", matchType: .endsWith), actualValue: "abcd") == nil)
        #expect(
            RuleEvaluator.evaluate(.init(name: "a", match: "^ab.+", matchType: .matchesRegex), actualValue: "abcd")
                == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", matchType: .isEmpty), actualValue: "") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", matchType: .notEmpty), actualValue: "x") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "1", matchType: .gt), actualValue: "2") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "2", matchType: .gte), actualValue: "2") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "3", matchType: .lt), actualValue: "2") == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "2", matchType: .lte), actualValue: "2") == nil)
    }

    @Test("Returns mismatch for failing rule")
    func mismatch() {
        let failure = RuleEvaluator.evaluate(
            .init(name: "Accept", match: "application/json", matchType: .equalTo),
            actualValue: "text/plain"
        )
        switch failure {
        case .mismatch(let expectedDescription, let actualValue):
            #expect(expectedDescription.contains("application/json"))
            #expect(actualValue == "text/plain")
        default:
            Issue.record("Expected mismatch failure")
        }
    }

    @Test("Negated rules pass when the value is absent unless required")
    func negatedRulesAllowAbsence() {
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "x", matchType: .notEqualTo), actualValue: nil) == nil)
        #expect(RuleEvaluator.evaluate(.init(name: "a", match: "x", matchType: .notContains), actualValue: nil) == nil)
        let required = RuleEvaluator.evaluate(
            .init(name: "a", match: "x", required: true, matchType: .notEqualTo), actualValue: nil)
        #expect(required == .missing(name: ""))
    }

    @Test("required: false makes a comparison optional but still enforced when present")
    func optionalRulesEnforcedWhenPresent() {
        let rule = RuleMatcher(name: "a", match: "^v[0-9]+$", required: false, matchType: .matchesRegex)
        #expect(RuleEvaluator.evaluate(rule, actualValue: nil) == nil)
        #expect(RuleEvaluator.evaluate(rule, actualValue: "v2") == nil)
        #expect(RuleEvaluator.evaluate(rule, actualValue: "beta") != nil)
    }

    @Test("Comparisons without required still treat an absent value as missing")
    func defaultComparisonRequiresValue() {
        #expect(
            RuleEvaluator.evaluate(.init(name: "a", match: "x", matchType: .equalTo), actualValue: nil)
                == .missing(name: ""))
    }
}
