import Foundation
import Testing
@testable import MoqCore

@Suite("RequestValidator - Unit Tests")
struct RequestValidatorTests {
    let validator = RequestValidator()

    func makeEndpoint(
        requiredQueryParameters: [String] = [],
        requiredHeaders: [String] = [],
        requiresBody: Bool = false,
        acceptedContentTypes: [String] = []
    ) -> Endpoint {
        Endpoint(
            key: EndpointKey(method: .get, path: "/test"),
            authRequirement: .none,
            variants: [ResponseVariant(name: "default")],
            requiredQueryParameters: requiredQueryParameters,
            requiredHeaders: requiredHeaders,
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
        let endpoint = makeEndpoint(requiredQueryParameters: ["id"])
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
        #expect(result?.message.contains("Missing required query parameter") == true)
    }

    @Test("Present required query parameter passes")
    func presentQuery() {
        let endpoint = makeEndpoint(requiredQueryParameters: ["id"])
        let ctx = RequestContext(queryParameters: ["id": "123"])
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result == nil)
    }

    @Test("Missing required header fails")
    func missingHeader() {
        let endpoint = makeEndpoint(requiredHeaders: ["X-Trace-Id"])
        let result = validator.validate(endpoint: endpoint, context: RequestContext())
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
    }

    @Test("Missing body when required fails")
    func missingBody() {
        let endpoint = makeEndpoint(requiresBody: true)
        let result = validator.validate(endpoint: endpoint, context: RequestContext(hasBody: false))
        #expect(result != nil)
        #expect(result?.statusCode == .badRequest)
    }

    @Test("Wrong content type fails")
    func wrongContentType() {
        let endpoint = makeEndpoint(acceptedContentTypes: ["application/json"])
        let ctx = RequestContext(hasBody: true, contentType: "text/plain")
        let result = validator.validate(endpoint: endpoint, context: ctx)
        #expect(result != nil)
        #expect(result?.statusCode == .unsupportedMediaType)
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
