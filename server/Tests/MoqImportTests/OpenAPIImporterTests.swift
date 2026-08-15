import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport

struct OpenAPIImporterTests {
    @Test("Parses a basic OpenAPI 3.0 spec")
    func parsesBasicSpec() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /users:
                get:
                  summary: List users
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.title == "Sample API")
        #expect(parsed.version == "1.0.0")
        let endpoint = try #require(parsed.endpoints.first)
        #expect(endpoint.method == "GET")
        #expect(endpoint.path == "/users")
        #expect(endpoint.alias == "List users")
    }

    @Test("Preserves endpoint tags")
    func preservesTags() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: Tagged API
              version: 1.0.0
            paths:
              /videos:
                get:
                  summary: List videos
                  tags: [youtube, catalog]
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.tags == ["youtube", "catalog"])
    }

    @Test("Falls back to a humanized operationId when summary is absent")
    func fallsBackToOperationId() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                get:
                  operationId: listAllUsers
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.alias == "List All Users")
        #expect(parsed.endpoints.first?.referenceName == "listAllUsers")
    }

    @Test("Falls back to a default alias when neither summary nor operationId is present")
    func fallsBackToDefaultAlias() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users/{id}:
                get:
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.alias == "Get Users By Id")
    }

    @Test("Extracts a JSON body from a response example")
    func extractsBodyFromExample() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          example:
                            id: 1
                            name: Ada
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let body = try #require(parsed.endpoints.first?.responses.first?.body)
        let data = try #require(body.data(using: .utf8))
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(object?["id"] as? Int == 1)
        #expect(object?["name"] as? String == "Ada")
    }

    @Test("Generates a stub body from an object schema when no example is present")
    func generatesStubFromSchema() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let body = try #require(parsed.endpoints.first?.responses.first?.body)
        let data = try #require(body.data(using: .utf8))
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(object?["id"] as? Int == 0)
        #expect(object?["name"] as? String == "string")
    }

    @Test("Generates a stub for array schemas")
    func generatesArrayStub() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: string
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.responses.first?.body == "[\"string\"]")
    }

    @Test("Resolves bearer auth from a security scheme")
    func resolvesBearerAuth() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
            security:
              - bearerAuth: []
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.authType == .bearer)
    }

    @Test("Resolves API key auth with the header name")
    func resolvesAPIKeyAuth() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            components:
              securitySchemes:
                apiKeyAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key
            security:
              - apiKeyAuth: []
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        #expect(parsed.endpoints.first?.authType == .apiKey)
        #expect(parsed.endpoints.first?.authHeaderName == "X-API-Key")
    }

    @Test("Extracts required headers and query parameters")
    func extractsRequestRules() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                get:
                  parameters:
                    - name: X-Request-Id
                      in: header
                      required: true
                      schema:
                        type: string
                    - name: limit
                      in: query
                      required: true
                      schema:
                        type: integer
                        example: 10
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let endpoint = try #require(parsed.endpoints.first)
        #expect(endpoint.requiredHeaders == ["X-Request-Id"])
        #expect(endpoint.queryParameters.first?.name == "limit")
        #expect(endpoint.queryParameters.first?.required == true)
    }

    @Test("Rejects Swagger 2.0 specs with a descriptive error")
    func rejectsSwagger2() {
        let spec = """
            {
              "swagger": "2.0",
              "info": { "title": "Legacy API", "version": "v1" },
              "paths": {
                "/pets": {
                  "get": { "responses": { "200": { "description": "OK" } } }
                }
              }
            }
            """
        #expect(throws: OpenAPIImportError.self) {
            try OpenAPIImporter.parse(spec)
        }
    }

    @Test("Throws a descriptive error for unparseable content")
    func throwsForGarbage() {
        #expect(throws: OpenAPIImportError.self) {
            try OpenAPIImporter.parse("not a spec at all")
        }
    }

    @Test("Assigns a default 200 JSON variant when an operation has no responses content")
    func defaultsToEmptyResponses() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /ping:
                get:
                  responses: {}
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let response = try #require(parsed.endpoints.first?.responses.first)
        #expect(response.statusCode == 200)
        #expect(response.name == "default")
    }

    @Test("Extracts requiresBody and acceptedContentTypes from a required JSON request body")
    func extractsRequestBodyMetadata() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /users:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                      application/xml:
                        schema:
                          type: object
                  responses:
                    "201":
                      description: Created
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let endpoint = try #require(parsed.endpoints.first)
        #expect(endpoint.requiresBody)
        #expect(endpoint.acceptedContentTypes == ["application/json", "application/xml"])
    }

    @Test("requiresBody and acceptedContentTypes default to empty when there is no request body")
    func requestBodyMetadataDefaultsEmpty() throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /ping:
                get:
                  responses:
                    "200":
                      description: OK
            """
        let parsed = try OpenAPIImporter.parse(spec)
        let endpoint = try #require(parsed.endpoints.first)
        #expect(!endpoint.requiresBody)
        #expect(endpoint.acceptedContentTypes.isEmpty)
    }
}
