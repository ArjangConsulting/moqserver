import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport

struct OpenAPISecurityImportTests {
    private func spec(
        security: String, extraSchemes: String = "", operation: String = "", version: String = "3.0.3"
    ) -> String {
        """
        openapi: \(version)
        info:
          title: API
          version: "1.0"
        components:
          securitySchemes:
            bearerAuth:
              type: http
              scheme: bearer
            sessionCookie:
              type: apiKey
              in: cookie
              name: example_session
            deviceKey:
              type: apiKey
              in: header
              name: X-App-Device
            queryKey:
              type: apiKey
              in: query
              name: key
        \(extraSchemes)
        security:
        \(security)
        paths:
          /v1/items:
            get:
        \(operation)
              responses:
                "200":
                  description: The items.
        """
    }

    @Test("Every scheme in one requirement object is imported, the same way on every run")
    func combinedRequirementIsDeterministic() throws {
        let text = spec(
            security: """
                  - sessionCookie: []
                    deviceKey: []
                    bearerAuth: []
                    queryKey: []
                """)
        let first = try OpenAPIImporter.parse(text)
        for _ in 0..<20 { #expect(try OpenAPIImporter.parse(text) == first) }

        let endpoint = try #require(first.endpoints.first)
        #expect(endpoint.authType == .bearer)
        #expect(endpoint.requiredHeaders == ["X-App-Device"])
        #expect(endpoint.cookies == [RuleMatcher(name: "example_session", required: true)])
        #expect(endpoint.requiredQueryParameters == ["key"])
        #expect(first.warnings.isEmpty)
    }

    @Test("A cookie API key becomes a cookie rule, not a header")
    func cookieAPIKey() throws {
        let parsed = try OpenAPIImporter.parse(spec(security: "  - sessionCookie: []"))
        let endpoint = try #require(parsed.endpoints.first)
        #expect(endpoint.authType == .none)
        #expect(endpoint.authHeaderName == nil)
        #expect(endpoint.requiredHeaders.isEmpty)
        #expect(endpoint.cookies.map(\.name) == ["example_session"])
    }

    @Test("A declared cookie parameter keeps its rule when security names the same cookie")
    func declaredCookieWins() throws {
        let parsed = try OpenAPIImporter.parse(
            spec(
                security: "  - sessionCookie: []",
                operation: """
                          parameters:
                            - name: example_session
                              in: cookie
                              required: true
                              schema:
                                type: string
                              example: abc
                    """))
        let cookies = try #require(parsed.endpoints.first).cookies
        #expect(cookies == [RuleMatcher(name: "example_session", required: true)])
    }

    @Test("Alternatives and unsupported schemes are reported")
    func reportsSkipped() throws {
        let parsed = try OpenAPIImporter.parse(
            spec(
                security: """
                      - bearerAuth: []
                        mtls: []
                      - deviceKey: []
                    """,
                extraSchemes: """
                        mtls:
                          type: mutualTLS
                    """,
                version: "3.1.0"))
        #expect(parsed.endpoints.first?.authType == .bearer)
        #expect(
            parsed.warnings == [
                "GET /v1/items: 2 alternative security requirements; imported only the first (bearerAuth + mtls).",
                "GET /v1/items: security scheme mtls (mutualTLS) is not supported and was skipped.",
            ])
    }

    @Test("Two Authorization schemes in one requirement keep the first and report the other")
    func conflictingAuthorization() throws {
        let parsed = try OpenAPIImporter.parse(
            spec(
                security: """
                      - bearerAuth: []
                        basicAuth: []
                    """,
                extraSchemes: """
                        basicAuth:
                          type: http
                          scheme: basic
                    """))
        #expect(parsed.endpoints.first?.authType == .basic)
        #expect(
            parsed.warnings == [
                "GET /v1/items: security scheme bearerAuth also uses the Authorization header and was skipped."
            ])
    }

    @Test("Response headers with an example carry into the variant; others are reported")
    func responseHeaders() throws {
        let text = """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /v1/session:
                post:
                  responses:
                    "200":
                      description: Signed in.
                      headers:
                        Set-Cookie:
                          schema:
                            type: string
                          example: example_session=abc; Path=/; HttpOnly
                        X-Rate-Limit:
                          schema:
                            type: integer
                            example: 100
                        X-Trace:
                          schema:
                            type: string
                      content:
                        application/json:
                          example: {"ok": true}
            """
        let parsed = try OpenAPIImporter.parse(text)
        let headers = try #require(parsed.endpoints.first?.responses.first).headers
        #expect(headers["Set-Cookie"] == "example_session=abc; Path=/; HttpOnly")
        #expect(headers["X-Rate-Limit"] == "100")
        #expect(headers["Content-Type"] == "application/json")
        #expect(headers["X-Trace"] == nil)
        #expect(
            parsed.warnings == [
                "POST /v1/session 200: response header(s) X-Trace have no example and were not imported."
            ])
    }
}

struct OpenAPIVariantNamingTests {
    @Test("Variants are named after the status, with the response description kept as description")
    func statusPhraseNames() throws {
        let parsed = try OpenAPIImporter.parse(
            """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /v1/items:
                get:
                  responses:
                    "200":
                      description: A page of items.
                      content:
                        application/json:
                          example: {"items": []}
                        application/xml:
                          example: <items/>
                    "401":
                      description: The token is missing or expired.
                    "418":
                      description: Unusual.
                    "5XX":
                      description: Something broke.
            """)
        let responses = try #require(parsed.endpoints.first).responses
        #expect(responses.map(\.name) == ["ok", "ok_xml", "unauthorized", "status418", "serverError"])
        #expect(responses.map(\.statusCode) == [200, 200, 401, 418, 500])
        #expect(responses.first?.description == "A page of items.")

        let project = ImportConverter.convert(parsed, selection: ImportSelection(), name: "API", path: "/tmp/api")
        let variants = try #require(project.endpoints.first).variants
        #expect(variants.map(\.name) == ["ok", "ok_xml", "unauthorized", "status418", "serverError"])
        #expect(variants.map(\.referenceName) == ["ok", "ok_xml", "unauthorized", "status418", "serverError"])
        #expect(variants.first?.isDefault == true)
        #expect(variants[2].description == "The token is missing or expired.")
    }

    @Test("Several named examples become one variant each")
    func namedExamples() throws {
        let parsed = try OpenAPIImporter.parse(
            """
            openapi: 3.0.3
            info:
              title: API
              version: "1.0"
            paths:
              /v1/items:
                get:
                  responses:
                    "200":
                      description: A page of items.
                      content:
                        application/json:
                          examples:
                            empty:
                              value: {"items": []}
                            full page:
                              value: {"items": [1, 2]}
            """)
        let responses = try #require(parsed.endpoints.first).responses
        #expect(responses.map(\.name) == ["ok_empty", "ok_fullPage"])
        #expect(responses.map(\.body) == [#"{"items":[]}"#, #"{"items":[1,2]}"#])
    }
}
