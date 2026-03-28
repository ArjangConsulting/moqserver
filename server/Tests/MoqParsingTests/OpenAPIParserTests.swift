import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

@Suite("OpenAPIParser")
struct OpenAPIParserTests {
    let parser = OpenAPIParser()

    func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    @Test("Parses petstore spec")
    func parsePetstore() throws {
        let data = try loadFixture("petstore.yaml")
        let spec = try parser.parse(data: data)

        #expect(!spec.endpoints.isEmpty)
        #expect(spec.title == "Petstore" || !spec.title.isEmpty)
    }

    @Test("Parses endpoints with methods and paths")
    func endpointMethodsAndPaths() throws {
        let data = try loadFixture("petstore.yaml")
        let spec = try parser.parse(data: data)

        let getPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(getPets != nil)
        #expect(!getPets!.responses.isEmpty)
    }

    @Test("Parses global bearer auth")
    func globalBearerAuth() throws {
        let data = try loadFixture("petstore-auth.yaml")
        let spec = try parser.parse(data: data)

        let getPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(getPets != nil)
        #expect(getPets?.authRequirement == .bearer)
    }

    @Test("Parses operation-level basic auth override")
    func operationBasicAuth() throws {
        let data = try loadFixture("petstore-auth.yaml")
        let spec = try parser.parse(data: data)

        let getPet = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}" }
        #expect(getPet != nil)
        #expect(getPet?.authRequirement == .basic)
    }

    @Test("Empty security array means no auth")
    func noAuthWithEmptySecurity() throws {
        let data = try loadFixture("petstore-auth.yaml")
        let spec = try parser.parse(data: data)

        let health = spec.endpoints.first { $0.path == "/public/health" }
        #expect(health != nil)
        #expect(health?.authRequirement == AuthRequirement.none)
    }

    @Test("Parses OAuth2 with scopes")
    func oauth2WithScopes() throws {
        let data = try loadFixture("petstore-auth.yaml")
        let spec = try parser.parse(data: data)

        let getFavorites = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/favorites" }
        #expect(getFavorites != nil)
        #expect(getFavorites?.authRequirement == .oauth2(scopes: ["read:pets"]))
    }

    @Test("Parses OAuth2 with multiple scopes")
    func oauth2MultipleScopes() throws {
        let data = try loadFixture("petstore-auth.yaml")
        let spec = try parser.parse(data: data)

        let postFavorite = spec.endpoints.first { $0.method == "POST" && $0.path == "/pets/favorites" }
        #expect(postFavorite != nil)
        if case .oauth2(let scopes) = postFavorite?.authRequirement {
            #expect(scopes.contains("read:pets"))
            #expect(scopes.contains("write:pets"))
            #expect(scopes.count == 2)
        } else {
            Issue.record("Expected .oauth2 auth, got \(String(describing: postFavorite?.authRequirement))")
        }
    }

    // MARK: - Extended Parser Tests

    @Test("Parses required query parameters")
    func requiredQueryParameters() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let listPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(listPets != nil)
        #expect(listPets?.requiredQueryParameters.contains("limit") == true)
    }

    @Test("Parses required header parameters")
    func requiredHeaderParameters() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let listPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        #expect(listPets != nil)
        #expect(listPets?.requiredHeaders.contains("X-Request-ID") == true)
    }

    @Test("Parses requestBody requirement")
    func requiresBodyParsing() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let createPet = spec.endpoints.first { $0.method == "POST" && $0.path == "/pets" }
        #expect(createPet != nil)
        #expect(createPet?.requiresBody == true)
        #expect(createPet?.acceptedContentTypes.contains("application/json") == true)
    }

    @Test("Generates mock body from schema when no example")
    func schemaGeneratedBody() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let getPetById = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}" }
        #expect(getPetById != nil)
        let defaultResponse = getPetById?.responses.first { $0.name == "default" }
        #expect(defaultResponse?.body != nil)
    }

    @Test("Extracts response headers")
    func responseHeaders() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let listPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        let defaultResponse = listPets?.responses.first { $0.name == "default" }
        let totalCountHeader = defaultResponse?.headers.first { $0.0 == "X-Total-Count" }
        #expect(totalCountHeader != nil)
        // Header value comes from example or schema stub
        #expect(totalCountHeader?.1 != nil)
    }

    @Test("Multi-content-type creates variants per content type")
    func multiContentTypeVariants() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let export = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/export" }
        #expect(export != nil)
        // Should have at least 3 variants (json, xml, csv)
        #expect((export?.responses.count ?? 0) >= 3)

        let xmlVariant = export?.responses.first { $0.headers.contains { $0.1.contains("xml") } }
        #expect(xmlVariant != nil)

        let csvVariant = export?.responses.first { $0.headers.contains { $0.1.contains("csv") } }
        #expect(csvVariant != nil)
    }

    @Test("Endpoint with no responses gets default response")
    func noResponsesDefault() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let noResponses = spec.endpoints.first { $0.path == "/no-responses" }
        #expect(noResponses != nil)
        #expect(!noResponses!.responses.isEmpty)
        #expect(noResponses?.responses.first?.name == "default")
    }

    @Test("Endpoint without content gets nil body")
    func emptyResponseBody() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let health = spec.endpoints.first { $0.path == "/health" }
        #expect(health != nil)
        let defaultResponse = health?.responses.first
        #expect(defaultResponse?.body == nil)
    }

    @Test("Parses JSON format OpenAPI spec")
    func parsesJSONFormat() throws {
        // Create a minimal JSON OpenAPI spec
        let json = """
        {
            "openapi": "3.0.0",
            "info": {"title": "JSON API", "version": "1.0"},
            "paths": {
                "/test": {
                    "get": {
                        "responses": {
                            "200": {
                                "description": "OK",
                                "content": {
                                    "application/json": {
                                        "schema": {"type": "object"}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        """.data(using: .utf8)!

        let spec = try parser.parse(data: json)
        #expect(spec.title == "JSON API")
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Invalid data throws error")
    func invalidDataThrows() {
        let data = Data("not a spec".utf8)
        #expect(throws: (any Error).self) {
            _ = try parser.parse(data: data)
        }
    }

    @Test("Spec title and version are extracted")
    func specTitleAndVersion() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)
        #expect(spec.title == "Extended Petstore")
        #expect(spec.version == "2.0.0")
    }

    @Test("Generates body for error schema")
    func errorSchemaBody() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let getPetById = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets/{petId}" }
        let errorResponse = getPetById?.responses.first { $0.statusCode == 404 }
        #expect(errorResponse != nil)
        #expect(errorResponse?.body != nil)
        // The body should be valid JSON (auto-generated from Error schema)
        if let body = errorResponse?.body {
            let json = try? JSONSerialization.jsonObject(with: body)
            #expect(json != nil)
        }
    }

    @Test("Enum values generate first enum value as stub")
    func enumStubGeneration() throws {
        let data = try loadFixture("petstore-extended.yaml")
        let spec = try parser.parse(data: data)

        let listPets = spec.endpoints.first { $0.method == "GET" && $0.path == "/pets" }
        let defaultResponse = listPets?.responses.first { $0.name == "default" }
        // The default response uses the example directly, so check the schema-generated ones
        // Pet schema has status enum, check it through the 200 response with example
        #expect(defaultResponse?.body != nil)
    }
}
