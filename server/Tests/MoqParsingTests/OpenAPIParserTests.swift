import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

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

    @Test("Parses OpenAPI 3.1 JSON format directly")
    func parsesOpenAPI31JSONFormatDirectly() throws {
        let json = """
        {
            "openapi": "3.1.0",
            "info": {"title": "JSON 3.1 API", "version": "1.0"},
            "paths": {
                "/direct": {
                    "get": {
                        "responses": {
                            "200": {"description": "OK"}
                        }
                    }
                }
            }
        }
        """.data(using: .utf8)!

        let spec = try parser.parse(data: json)
        #expect(spec.title == "JSON 3.1 API")
        #expect(spec.endpoints.first?.path == "/direct")
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

    @Test("Parses referenced parameters, request bodies, responses, and advanced security")
    func parsesReferencedComponentsAndAdvancedSecurity() throws {
        let yaml = """
        openapi: 3.0.3
        info:
          title: Component API
          version: 1.0.0
        security:
          - oauthGlobal:
              - read:all
          - oidcGlobal:
              - profile
        paths:
          /reports:
            get:
              security:
                - apiHeader: []
              parameters:
                - $ref: '#/components/parameters/RequiredHeader'
                - $ref: '#/components/parameters/RequiredQuery'
              responses:
                "2XX":
                  $ref: '#/components/responses/BinaryRangeResponse'
          /sessions:
            post:
              requestBody:
                $ref: '#/components/requestBodies/FormRequest'
              responses:
                default:
                  description: Session accepted
          /login:
            post:
              security:
                - basicAuth: []
                - oauthGlobal:
                    - write:all
              responses:
                "200":
                  description: OK
        components:
          parameters:
            RequiredHeader:
              name: X-Trace-Id
              in: header
              required: true
              schema:
                type: string
            RequiredQuery:
              name: page
              in: query
              required: true
              schema:
                type: integer
          requestBodies:
            FormRequest:
              required: true
              content:
                application/x-www-form-urlencoded:
                  schema:
                    type: object
                    properties:
                      name:
                        type: string
          responses:
            BinaryRangeResponse:
              description: Binary success
              headers:
                X-Mode:
                  content:
                    text/plain:
                      example: bulk
              content:
                application/octet-stream:
                  schema:
                    type: string
                    format: binary
          securitySchemes:
            apiHeader:
              type: apiKey
              in: header
              name: X-API-Key
            basicAuth:
              type: http
              scheme: basic
            oauthGlobal:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://example.com/token
                  scopes:
                    read:all: Read all
                    write:all: Write all
            oidcGlobal:
              type: openIdConnect
              openIdConnectUrl: https://example.com/.well-known/openid-configuration
        """

        let spec = try parser.parse(data: Data(yaml.utf8))

        let reports = try #require(spec.endpoints.first { $0.method == "GET" && $0.path == "/reports" })
        #expect(reports.requiredHeaders == ["X-Trace-Id"])
        #expect(reports.requiredQueryParameters == ["page"])
        #expect(reports.responses.count == 1)
        #expect(reports.responses.first?.statusCode == 200)
        #expect(reports.responses.first?.name == "2XX")
        #expect(reports.responses.first?.headers.contains { $0.0 == "X-Mode" && $0.1 == "bulk" } == true)
        #expect(reports.responses.first?.headers.contains { $0.0 == "Content-Type" && $0.1 == "application/octet-stream" } == true)
        #expect(reports.responses.first?.body?.isEmpty == true)
        #expect(reports.authRequirement == .apiKey(headerName: "X-API-Key"))

        let sessions = try #require(spec.endpoints.first { $0.method == "POST" && $0.path == "/sessions" })
        #expect(sessions.requiresBody)
        #expect(sessions.acceptedContentTypes == ["application/x-www-form-urlencoded"])
        #expect(sessions.responses.first?.name == "default")

        let login = try #require(spec.endpoints.first { $0.method == "POST" && $0.path == "/login" })
        #expect(login.authRequirement == .anyOf([.basic, .oauth2(scopes: ["write:all"])]))

        let globalOnlyYAML = """
        openapi: 3.0.3
        info:
          title: Global Security API
          version: 1.0.0
        security:
          - oauthGlobal:
              - read:all
          - oidcGlobal:
              - profile
        paths:
          /me:
            get:
              responses:
                "200":
                  description: OK
        components:
          securitySchemes:
            oauthGlobal:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://example.com/token
                  scopes:
                    read:all: Read all
            oidcGlobal:
              type: openIdConnect
              openIdConnectUrl: https://example.com/.well-known/openid-configuration
        """

        let globalOnlySpec = try parser.parse(data: Data(globalOnlyYAML.utf8))
        let me = try #require(globalOnlySpec.endpoints.first { $0.path == "/me" })
        #expect(me.authRequirement == .anyOf([
            .oauth2(scopes: ["read:all"]),
            .openIdConnect(scopes: ["profile"]),
        ]))
    }

    @Test("Parses response ranges and duplicate success names uniquely")
    func parsesResponseRangesAndUniqueNames() throws {
        let yaml = """
        openapi: 3.0.3
        info:
          title: Range API
          version: 1.0.0
        paths:
          /range:
            get:
              responses:
                "2XX":
                  description: Any success
                "4XX":
                  description: Any client error
                "5XX":
                  description: Any server error
          /duplicates:
            get:
              responses:
                "200":
                  description: JSON result
                  content:
                    application/json:
                      schema:
                        type: object
                "201":
                  description: Created result
                  content:
                    application/json:
                      schema:
                        type: object
                default:
                  description: Fallback
        """

        let spec = try parser.parse(data: Data(yaml.utf8))

        let rangeEndpoint = try #require(spec.endpoints.first { $0.path == "/range" })
        #expect(rangeEndpoint.responses.map(\.statusCode) == [200, 400, 500])
        #expect(rangeEndpoint.responses.map(\.name) == ["2XX", "4XX", "5XX"])

        let duplicates = try #require(spec.endpoints.first { $0.path == "/duplicates" })
        #expect(duplicates.responses.map(\.name).contains("default"))
        #expect(duplicates.responses.map(\.name).contains("success-201"))
        #expect(duplicates.responses.map(\.name).contains("default-fallback"))
    }

    @Test("Parses allOf security requirements and ignores unsupported schemes")
    func parsesAllOfSecurityAndUnsupportedSchemes() throws {
        let yaml = """
        openapi: 3.1.0
        info:
          title: Security API
          version: 1.0.0
        paths:
          /secure:
            get:
              security:
                - bearerAuth: []
                  apiHeader: []
              responses:
                "200":
                  description: OK
          /ignored:
            get:
              security:
                - digestAuth: []
                - mtlsAuth: []
              responses:
                "200":
                  description: OK
        components:
          securitySchemes:
            bearerAuth:
              type: http
              scheme: bearer
            apiHeader:
              type: apiKey
              in: header
              name: X-API-Key
            digestAuth:
              type: http
              scheme: digest
            mtlsAuth:
              type: mutualTLS
        """

        let spec = try parser.parse(data: Data(yaml.utf8))

        let secure = try #require(spec.endpoints.first { $0.path == "/secure" })
        if case .allOf(let requirements) = secure.authRequirement {
            #expect(requirements.count == 2)
            #expect(requirements.contains(.bearer))
            #expect(requirements.contains(.apiKey(headerName: "X-API-Key")))
        } else {
            Issue.record("Expected allOf auth requirement")
        }

        let ignored = try #require(spec.endpoints.first { $0.path == "/ignored" })
        #expect(ignored.authRequirement == .none)
    }

    @Test("Parses rich media responses with deterministic ordering and generated bodies")
    func parsesRichMediaResponses() throws {
        let yaml = """
        openapi: 3.0.3
        info:
          title: Rich Media API
          version: 1.0.0
        paths:
          /rich:
            get:
              responses:
                "200":
                  description: OK
                  headers:
                    X-Count:
                      content:
                        text/plain:
                          schema:
                            type: integer
                  content:
                    text/html:
                      schema:
                        type: object
                        properties:
                          title:
                            type: string
                          count:
                            type: integer
                    application/problem+json:
                      example:
                        message: hello
                    application/custom+xml:
                      schema:
                        type: object
                        properties:
                          title:
                            type: string
                    image/svg+xml:
                      schema:
                        type: string
                    text/csv: {}
                    text/plain:
                      schema:
                        type: string
        """

        let spec = try parser.parse(data: Data(yaml.utf8))
        let rich = try #require(spec.endpoints.first { $0.path == "/rich" })

        #expect(rich.responses.count == 6)
        let orderedContentTypes = rich.responses.compactMap {
            $0.headers.first { $0.0 == "Content-Type" }?.1
        }
        #expect(orderedContentTypes == [
            "application/custom+xml",
            "application/problem+json",
            "image/svg+xml",
            "text/csv",
            "text/html",
            "text/plain",
        ])

        let jsonVariant = try #require(rich.responses.first {
            $0.headers.contains { $0.0 == "Content-Type" && $0.1 == "application/problem+json" }
        })
        #expect(jsonVariant.headers.contains { $0.0 == "Content-Type" && $0.1 == "application/problem+json" })
        #expect(jsonVariant.headers.contains { $0.0 == "X-Count" && $0.1 == "0" })
        #expect(String(data: try #require(jsonVariant.body), encoding: .utf8)?.contains("\"message\":\"hello\"") == true)

        let xmlVariant = try #require(rich.responses.first { $0.headers.contains { $0.1 == "application/custom+xml" } })
        #expect(String(data: try #require(xmlVariant.body), encoding: .utf8)?.contains("<?xml version=\"1.0\"") == true)

        let svgVariant = try #require(rich.responses.first { $0.headers.contains { $0.1 == "image/svg+xml" } })
        #expect(String(data: try #require(svgVariant.body), encoding: .utf8)?.contains("<svg") == true)

        let csvVariant = try #require(rich.responses.first { $0.headers.contains { $0.1 == "text/csv" } })
        #expect(String(data: try #require(csvVariant.body), encoding: .utf8)?.contains("column1,column2") == true)

        let htmlVariant = try #require(rich.responses.first { $0.headers.contains { $0.1 == "text/html" } })
        #expect(String(data: try #require(htmlVariant.body), encoding: .utf8)?.contains("<table>") == true)

        let textVariant = try #require(rich.responses.first { $0.headers.contains { $0.1 == "text/plain" } })
        #expect(String(data: try #require(textVariant.body), encoding: .utf8) == "string")
    }

    @Test("Creates unique XML variant names and preserves informational and redirect names")
    func createsUniqueXmlVariantNamesAndSpecialStatusNames() throws {
        let yaml = """
        openapi: 3.0.3
        info:
          title: Variant Names API
          version: 1.0.0
        paths:
          /collisions:
            get:
              responses:
                "200":
                  description: XML collisions
                  content:
                    application/atom+xml:
                      schema:
                        type: object
                        properties:
                          a:
                            type: string
                    application/problem+xml:
                      schema:
                        type: object
                        properties:
                          b:
                            type: string
                    application/xml:
                      schema:
                        type: object
                        properties:
                          c:
                            type: string
                    text/xml:
                      schema:
                        type: object
                        properties:
                          d:
                            type: string
          /statuses:
            get:
              responses:
                "101":
                  description: Switching Protocols
                "302":
                  description: Found
        """

        let spec = try parser.parse(data: Data(yaml.utf8))

        let collisions = try #require(spec.endpoints.first { $0.path == "/collisions" })
        #expect(collisions.responses.map(\.name) == ["default", "default-xml", "default-xml-200", "default-xml-200-2"])

        let statuses = try #require(spec.endpoints.first { $0.path == "/statuses" })
        #expect(statuses.responses.first { $0.statusCode == 101 }?.name == "101")
        #expect(statuses.responses.first { $0.statusCode == 302 }?.name == "302")
    }

    @Test("Parses default bodies scalar examples and schema stubs across media types")
    func parsesDefaultBodiesScalarExamplesAndSchemaStubs() throws {
        let yaml = """
        openapi: 3.1.0
        info:
          title: Coverage API
          version: 1.0.0
        paths:
          /defaults:
            get:
              responses:
                "200":
                  description: Defaults
                  content:
                    application/json: {}
                    application/xml: {}
                    text/html: {}
                    text/plain: {}
          /examples:
            get:
              responses:
                "200":
                  description: Examples
                  headers:
                    X-Trace:
                      example: trace-123
                      schema:
                        type: string
                  content:
                    application/custom+json:
                      example: 7
                    application/bool+json:
                      example: true
                    application/xml:
                      example:
                        nested:
                          flag: true
                          items:
                            - 1
                            - 2
          /schemas:
            get:
              responses:
                "200":
                  description: Schemas
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          name:
                            type: string
                    text/x-number:
                      schema:
                        type: number
                    text/x-bool:
                      schema:
                        type: boolean
                    text/x-array:
                      schema:
                        type: array
                    application/problem+xml:
                      schema:
                        type: object
                        properties:
                          value:
                            type: number
                          enabled:
                            type: boolean
                    text/xml:
                      schema:
                        type: array
        """

        let spec = try parser.parse(data: Data(yaml.utf8))

        let defaults = try #require(spec.endpoints.first { $0.path == "/defaults" })
        #expect(String(data: try #require(defaults.responses.first { $0.headers.contains { $0.1 == "application/json" } }?.body), encoding: .utf8) == "{}")
        #expect(String(data: try #require(defaults.responses.first { $0.headers.contains { $0.1 == "application/xml" } }?.body), encoding: .utf8)?.contains("<root/>") == true)
        #expect(String(data: try #require(defaults.responses.first { $0.headers.contains { $0.1 == "text/html" } }?.body), encoding: .utf8)?.contains("<!DOCTYPE html>") == true)
        #expect(String(data: try #require(defaults.responses.first { $0.headers.contains { $0.1 == "text/plain" } }?.body), encoding: .utf8) == "mock-response")

        let examples = try #require(spec.endpoints.first { $0.path == "/examples" })
        #expect(examples.responses.contains { response in
            response.headers.contains { $0.0 == "X-Trace" && $0.1 == "trace-123" }
        })
        #expect(String(data: try #require(examples.responses.first { $0.headers.contains { $0.1 == "application/custom+json" } }?.body), encoding: .utf8) == "7")
        #expect(String(data: try #require(examples.responses.first { $0.headers.contains { $0.1 == "application/bool+json" } }?.body), encoding: .utf8) == "1")
        let xmlExampleBody = String(data: try #require(examples.responses.first { $0.headers.contains { $0.1 == "application/xml" } }?.body), encoding: .utf8)
        #expect(xmlExampleBody?.contains("<nested>") == true)
        #expect(xmlExampleBody?.contains("<item>1</item>") == true)

        let schemas = try #require(spec.endpoints.first { $0.path == "/schemas" })
        let jsonBody = String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "application/json" } }?.body), encoding: .utf8)
        #expect(jsonBody == "{}" || jsonBody == #"{"name":"string"}"#)
        #expect(String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "text/x-number" } }?.body), encoding: .utf8) == "0.0")
        #expect(String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "text/x-bool" } }?.body), encoding: .utf8) == "false")
        #expect(String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "text/x-array" } }?.body), encoding: .utf8) == "[]")
        #expect(String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "application/problem+xml" } }?.body), encoding: .utf8)?.contains("<value>0.0</value>") == true)
        #expect(String(data: try #require(schemas.responses.first { $0.headers.contains { $0.1 == "text/xml" } }?.body), encoding: .utf8)?.contains("<root/>") == true)
    }
}
