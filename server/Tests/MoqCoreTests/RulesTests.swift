import Foundation
import Testing
@testable import MoqCore

@Suite("GlobalRules, RequestRules, RuleMatcher — Codable & Init Tests")
struct RulesTests {
    // MARK: - RuleMatcher

    @Test("RuleMatcher init with all fields")
    func ruleMatcherInit() {
        let matcher = RuleMatcher(name: "Authorization", match: "^Bearer .+", required: true)
        #expect(matcher.name == "Authorization")
        #expect(matcher.match == "^Bearer .+")
        #expect(matcher.required == true)
    }

    @Test("RuleMatcher init with defaults")
    func ruleMatcherDefaults() {
        let matcher = RuleMatcher(name: "X-Custom")
        #expect(matcher.name == "X-Custom")
        #expect(matcher.match == nil)
        #expect(matcher.required == nil)
    }

    @Test("RuleMatcher encode/decode roundtrip")
    func ruleMatcherCodable() throws {
        let original = RuleMatcher(name: "Accept", match: "application/json", required: true)
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(RuleMatcher.self, from: data)
        #expect(decoded == original)
    }

    @Test("RuleMatcher equality")
    func ruleMatcherEquality() {
        let a = RuleMatcher(name: "X-Key", match: "abc", required: true)
        let b = RuleMatcher(name: "X-Key", match: "abc", required: true)
        let c = RuleMatcher(name: "X-Key", match: "def", required: false)
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
            queryParams: [RuleMatcher(name: "page", required: true)]
        )
        #expect(rules.headers?.count == 1)
        #expect(rules.verifyCookies == false)
        #expect(rules.queryParams?.count == 1)
    }

    @Test("RequestRules init with defaults")
    func requestRulesDefaults() {
        let rules = RequestRules()
        #expect(rules.headers == nil)
        #expect(rules.verifyCookies == nil)
        #expect(rules.queryParams == nil)
    }

    @Test("RequestRules encode/decode roundtrip with snake_case keys")
    func requestRulesCodable() throws {
        let original = RequestRules(
            headers: [RuleMatcher(name: "Accept")],
            verifyCookies: true,
            queryParams: [RuleMatcher(name: "limit", match: "\\d+")]
        )
        let data = try JSONEncoder().encode(original)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        #expect(json["verify_cookies"] != nil)
        #expect(json["query_params"] != nil)

        let decoded = try JSONDecoder().decode(RequestRules.self, from: data)
        #expect(decoded == original)
    }

    @Test("RequestRules decode from JSON with snake_case keys")
    func requestRulesDecodeSnakeCase() throws {
        let json = """
        {
            "headers": [{"name": "X-Custom", "required": true}],
            "verify_cookies": true,
            "query_params": [{"name": "page"}]
        }
        """.data(using: .utf8)!
        let rules = try JSONDecoder().decode(RequestRules.self, from: json)
        #expect(rules.headers?.first?.name == "X-Custom")
        #expect(rules.verifyCookies == true)
        #expect(rules.queryParams?.first?.name == "page")
    }
}
