import Foundation
import Testing
@testable import MoqCore

struct RequestValidatorTests {
    let validator = RequestValidator()

    func makeEndpoint(
        queryParamRules: [RuleMatcher] = [],
        headerRules: [RuleMatcher] = [],
        cookieRules: [RuleMatcher] = [],
        verifyCookies: Bool = false,
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = []
    ) -> Endpoint {
        Endpoint(
            key: EndpointKey(method: .get, path: "/test"),
            authRequirement: .none,
            variants: [ResponseVariant(name: "default")],
            queryParamRules: queryParamRules,
            headerRules: headerRules,
            cookieRules: cookieRules,
            verifyCookies: verifyCookies,
            requiresBody: requiresBody,
            acceptedContentTypes: acceptedContentTypes
        )
    }

    @Test("No requirements passes")
    func noRequirements() {
        let endpoint = makeEndpoint()
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result == nil)
    }

    @Test("Missing required query parameter fails")
    func missingQuery() {
        let endpoint = makeEndpoint(queryParamRules: [RuleMatcher(name: "id", required: true, matchType: .require)])
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
        #expect(result?.code == .missingRequiredQueryParam)
        #expect(result?.message.contains("Query parameter 'id' is required") == true)
    }

    @Test("Present required query parameter passes")
    func presentQuery() {
        let endpoint = makeEndpoint(queryParamRules: [RuleMatcher(name: "id", required: true, matchType: .require)])
        let ctx = RequestContext(queryParameters: ["id": "123"])
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result == nil)
    }

    @Test("Missing required header fails")
    func missingHeader() {
        let endpoint = makeEndpoint(headerRules: [RuleMatcher(name: "X-Trace-Id", required: true, matchType: .require)])
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
        #expect(result?.code == .missingRequiredHeader)
    }

    @Test("Header value mismatch fails with specific code")
    func headerValueMismatch() {
        let endpoint = makeEndpoint(headerRules: [RuleMatcher(name: "Accept", match: "application/json", matchType: .equalTo)])
        let result = validator.validate(
            endpoint: endpoint,
            context: RequestContext(headers: ["accept": "text/plain"])
        )
        #expect(result?.code == .headerValueMismatch)
    }

    @Test("Cookie rule validates not empty")
    func cookieRule() {
        let endpoint = makeEndpoint(cookieRules: [RuleMatcher(name: "session_id", matchType: .notEmpty)])
        let result = validator.validate(
            endpoint: endpoint,
            context: RequestContext(cookies: ["session_id": "abc123"])
        )
        #expect(result == nil)
    }

    @Test("verifyCookies requires at least one cookie")
    func verifyCookies() {
        let endpoint = makeEndpoint(verifyCookies: true)
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result?.code == .missingRequiredCookie)
    }

    @Test("Missing body when required fails")
    func missingBody() {
        let endpoint = makeEndpoint(requiresBody: true)
        let result = validator.validate(endpoint: endpoint, context: RequestContext(hasBody: false))
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
        #expect(result?.code == .missingRequestBody)
    }

    @Test("Wrong content type fails")
    func wrongContentType() {
        let endpoint = makeEndpoint(acceptedContentTypes: ["application/json"])
        let ctx = RequestContext(hasBody: true, contentType: "text/plain")
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result != nil)
        #expect(result?.statusCode == .unsupportedMediaType)
        #expect(result?.code == .unsupportedContentType)
    }

    @Test("Correct content type passes")
    func correctContentType() {
        let endpoint = makeEndpoint(requiresBody: true, acceptedContentTypes: ["application/json"])
        let ctx = RequestContext(hasBody: true, contentType: "application/json")
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result == nil)
    }

    @Test("Wildcard content type matches")
    func wildcardContentType() {
        let endpoint = makeEndpoint(acceptedContentTypes: ["*/*"])
        let ctx = RequestContext(hasBody: true, contentType: "application/json")
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result == nil)
    }
}
