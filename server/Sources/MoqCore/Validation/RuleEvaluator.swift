import Foundation
import Logging

private let logger = Logger(label: "moqserver.core.RuleEvaluator")

/// Evaluates request validation rules against actual request values.
public enum RuleEvaluator {
    public enum Failure: Sendable, Equatable {
        case missing(name: String)
        case mismatch(expectedDescription: String, actualValue: String?)
    }

    public static func evaluate(_ rule: RuleMatcher, actualValue: String?) -> Failure? {
        let matchType = resolvedMatchType(for: rule)
        logger.trace(
            "Evaluating rule '\(rule.name)' with matchType=\(matchType) against value=\(actualValue ?? "<nil>")")

        // "Optional, but if present it must match": an absent value passes any comparison when the
        // rule says `required: false`, and a negated comparison (`not_equal_to`, `not_contains`)
        // is trivially satisfied by absence unless the rule says `required: true`.
        if actualValue == nil, absenceSatisfies(rule, matchType: matchType) {
            return nil
        }

        // A comparison with nothing to compare against still honours `required: true`: the value
        // has to be sent. Without this a rule marked required but left without a value (as
        // Studio's Required checkbox used to write) accepted every request.
        if rule.match == nil, comparesValue(matchType) {
            return rule.required == true && !isPresent(actualValue) ? .missing(name: rule.name) : nil
        }

        switch matchType {
        case .require:
            return isPresent(actualValue) ? nil : .missing(name: rule.name)
        case .equalTo:
            return compare(actualValue, against: rule.match) { $0 == $1 }
        case .notEqualTo:
            return compare(actualValue, against: rule.match) { $0 != $1 }
        case .contains:
            return compare(actualValue, against: rule.match) { $0.contains($1) }
        case .notContains:
            return compare(actualValue, against: rule.match) { !$0.contains($1) }
        case .beginsWith:
            return compare(actualValue, against: rule.match) { $0.hasPrefix($1) }
        case .endsWith:
            return compare(actualValue, against: rule.match) { $0.hasSuffix($1) }
        case .matchesRegex:
            return compare(actualValue, against: rule.match) {
                $0.range(of: $1, options: .regularExpression) != nil
            }
        case .isEmpty:
            return (actualValue ?? "").isEmpty
                ? nil : .mismatch(expectedDescription: "be empty", actualValue: actualValue)
        case .notEmpty:
            return isPresent(actualValue)
                ? nil : .mismatch(expectedDescription: "not be empty", actualValue: actualValue)
        case .gt:
            return compareNumeric(actualValue, against: rule.match, symbol: ">") { $0 > $1 }
        case .gte:
            return compareNumeric(actualValue, against: rule.match, symbol: ">=") { $0 >= $1 }
        case .lt:
            return compareNumeric(actualValue, against: rule.match, symbol: "<") { $0 < $1 }
        case .lte:
            return compareNumeric(actualValue, against: rule.match, symbol: "<=") { $0 <= $1 }
        }
    }

    private static func resolvedMatchType(for rule: RuleMatcher) -> MatchType {
        if let matchType = rule.matchType {
            return matchType
        }
        if rule.required == true && rule.match == nil {
            return .require
        }
        return .equalTo
    }

    private static func absenceSatisfies(_ rule: RuleMatcher, matchType: MatchType) -> Bool {
        switch matchType {
        case .require, .isEmpty, .notEmpty:
            return false
        case .notEqualTo, .notContains:
            return rule.required != true
        case .equalTo, .contains, .beginsWith, .endsWith, .matchesRegex, .gt, .gte, .lt, .lte:
            return rule.required == false
        }
    }

    /// Match types that compare the request's value with `match`.
    private static func comparesValue(_ matchType: MatchType) -> Bool {
        switch matchType {
        case .require, .isEmpty, .notEmpty:
            return false
        case .equalTo, .notEqualTo, .contains, .notContains, .beginsWith, .endsWith, .matchesRegex, .gt, .gte, .lt,
            .lte:
            return true
        }
    }

    private static func isPresent(_ value: String?) -> Bool {
        guard let value else { return false }
        return !value.isEmpty
    }

    private static func compare(
        _ actualValue: String?,
        against expectedValue: String?,
        predicate: (String, String) -> Bool
    ) -> Failure? {
        guard let expectedValue else {
            return nil
        }
        guard let actualValue else {
            return .missing(name: "")
        }
        return predicate(actualValue, expectedValue)
            ? nil
            : .mismatch(expectedDescription: "match '\(expectedValue)'", actualValue: actualValue)
    }

    private static func compareNumeric(
        _ actualValue: String?,
        against expectedValue: String?,
        symbol: String,
        predicate: (Double, Double) -> Bool
    ) -> Failure? {
        guard let expectedValue else {
            return nil
        }
        guard let actualValue else {
            return .missing(name: "")
        }
        guard let actualNumber = Double(actualValue),
            let expectedNumber = Double(expectedValue)
        else {
            return .mismatch(expectedDescription: "be \(symbol) '\(expectedValue)'", actualValue: actualValue)
        }
        return predicate(actualNumber, expectedNumber)
            ? nil
            : .mismatch(expectedDescription: "be \(symbol) '\(expectedValue)'", actualValue: actualValue)
    }
}
