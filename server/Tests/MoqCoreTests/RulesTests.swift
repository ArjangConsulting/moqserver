import Foundation
import Testing
@testable import MoqCore

@Suite("GlobalRules, RequestRules, RuleMatcher — Codable & Init Tests")
struct RulesTests {
    // MARK: - RuleMatcher

    @Test("RuleMatcher init with all fields")
    func ruleMatcherInit() {
        let matcher = RuleMatcher(name: "Authorization", match: "^Bearer .+", required: true, matchType: .matchesRegex)
        #expect(matcher.name == "Authorization")
        #expect(matcher.match == "^Bearer .+")
        #expect(matcher.required == true)
        #expect(matcher.matchType == .matchesRegex)
    }

    @Test("RuleMatcher init with defaults")
    func ruleMatcherDefaults() {
        let matcher = RuleMatcher(name: "X-Custom")
        #expect(matcher.name == "X-Custom")
        #expect(matcher.match == nil)
        #expect(matcher.required == nil)
        #expect(matcher.matchType == nil)
    }

    @Test("RuleMatcher encode/decode roundtrip")
    func ruleMatcherCodable() throws {
        let original = RuleMatcher(name: "Accept", match: "application/json", required: true, matchType: .equalTo)
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(RuleMatcher.self, from: data)
        #expect(decoded == original)

        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        #expect(json["match_type"] as? String == "equal_to")
    }

    @Test("RuleMatcher equality")
    func ruleMatcherEquality() {
        let a = RuleMatcher(name: "X-Key", match: "abc", required: true, matchType: .equalTo)
        let b = RuleMatcher(name: "X-Key", match: "abc", required: true, matchType: .equalTo)
        let c = RuleMatcher(name: "X-Key", match: "def", required: false, matchType: .notEqualTo)
        #expect(a == b)
        #expect(a != c)
    }

    // MARK: - GlobalRules

    @Test("GlobalRules init with all fields")
    func globalRulesInit() {
        let rules = GlobalRules(
            requiredHeaders: [RuleMatcher(name: "X-Request-ID", required: true)],
            verifyCookies: true
        )
        #expect(rules.requiredHeaders?.count == 1)
        #expect(rules.verifyCookies == true)
    }

    @Test("GlobalRules init with defaults")
    func globalRulesDefaults() {
        let rules = GlobalRules()
        #expect(rules.requiredHeaders == nil)
        #expect(rules.verifyCookies == nil)
    }

    @Test("GlobalRules encode/decode roundtrip with snake_case keys")
    func globalRulesCodable() throws {
        let original = GlobalRules(
            requiredHeaders: [RuleMatcher(name: "Authorization", required: true)],
            verifyCookies: true
        )
        let data = try JSONEncoder().encode(original)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        // Verify snake_case keys
        #expect(json["required_headers"] != nil)
        #expect(json["verify_cookies"] != nil)

        let decoded = try JSONDecoder().decode(GlobalRules.self, from: data)
        #expect(decoded == original)
    }

    // MARK: - RequestRules

    @Test("RequestRules init with all fields")
    func requestRulesInit() {
        let rules = RequestRules(
            headers: [RuleMatcher(name: "Content-Type", match: "application/json")],
            verifyCookies: false,
            queryParams: [RuleMatcher(name: "page", required: true)],
            cookies: [RuleMatcher(name: "session_id", matchType: .notEmpty)]
        )
        #expect(rules.headers?.count == 1)
        #expect(rules.verifyCookies == false)
        #expect(rules.queryParams?.count == 1)
        #expect(rules.cookies?.count == 1)
    }

    @Test("RequestRules init with defaults")
    func requestRulesDefaults() {
        let rules = RequestRules()
        #expect(rules.headers == nil)
        #expect(rules.verifyCookies == nil)
        #expect(rules.queryParams == nil)
        #expect(rules.cookies == nil)
    }

    @Test("RequestRules encode/decode roundtrip with snake_case keys")
    func requestRulesCodable() throws {
        let original = RequestRules(
            headers: [RuleMatcher(name: "Accept")],
            verifyCookies: true,
            queryParams: [RuleMatcher(name: "limit", match: "\\d+", matchType: .matchesRegex)],
            cookies: [RuleMatcher(name: "session_id", matchType: .notEmpty)]
        )
        let data = try JSONEncoder().encode(original)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        #expect(json["verify_cookies"] != nil)
        #expect(json["query_params"] != nil)
        #expect(json["cookies"] != nil)

        let decoded = try JSONDecoder().decode(RequestRules.self, from: data)
        #expect(decoded == original)
    }

    @Test("RequestRules decode from JSON with snake_case keys")
    func requestRulesDecodeSnakeCase() throws {
        let json = """
        {
            "headers": [{"name": "X-Custom", "required": true, "match_type": "require"}],
            "verify_cookies": true,
            "query_params": [{"name": "page"}],
            "cookies": [{"name": "session_id", "match_type": "not_empty"}]
        }
        """.data(using: .utf8)!
        let rules = try JSONDecoder().decode(RequestRules.self, from: json)
        #expect(rules.headers?.first?.name == "X-Custom")
        #expect(rules.verifyCookies == true)
        #expect(rules.queryParams?.first?.name == "page")
        #expect(rules.headers?.first?.matchType == .require)
        #expect(rules.cookies?.first?.matchType == .notEmpty)
    }
}
