import Foundation
import Testing
@testable import MoqCore

@Suite("EndpointConverter - Unit Tests")
struct EndpointConverterTests {
    @Test("Converts parsed endpoint to domain endpoint")
    func convertEndpoint() {
        let parsed = ParsedEndpoint(
            method: "GET",
            path: "/users",
            responses: [
                ParsedResponse(name: "default", statusCode: 200, headers: [("Content-Type", "application/json")], body: Data("{}".utf8)),
                ParsedResponse(name: "error-404", statusCode: 404, headers: [], body: nil),
            ],
            authRequirement: .bearer,
            requiredQueryParameters: ["page"],
            requiredHeaders: ["Accept"],
            requiresBody: false,
            acceptedContentTypes: []
        )

        let endpoint = EndpointConverter.makeEndpoint(from: parsed)

        #expect(endpoint.key.method == .get)
        #expect(endpoint.key.path == "/users")
        #expect(endpoint.authRequirement == .bearer)
        #expect(endpoint.variants.count == 2)
        #expect(endpoint.variants[0].name == "default")
        #expect(endpoint.variants[0].statusCode == .ok)
        #expect(endpoint.variants[1].name == "error-404")
        #expect(endpoint.variants[1].statusCode == HTTPStatusCode(code: 404))
        #expect(endpoint.requiredQueryParameters == ["page"])
        #expect(endpoint.requiredHeaders == ["Accept"])
    }

    @Test("Converts full spec to endpoints")
    func convertSpec() {
        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(method: "GET", path: "/a", responses: [], authRequirement: .none),
                ParsedEndpoint(method: "POST", path: "/b", responses: [], authRequirement: .none),
            ]
        )

        let endpoints = EndpointConverter.makeEndpoints(from: spec)
        #expect(endpoints.count == 2)
    }
}
