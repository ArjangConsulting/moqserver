import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport

/// Ports the security- and behavior-critical cases from Kotlin's
/// `studio/studio-import/src/test/kotlin/com/moqserver/studio/imports/HARImportParserTest.kt`.
struct HARImporterTests {
    @Test("Parses a basic entry into an endpoint with one response")
    func parsesBasicEntry() throws {
        let har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": { "method": "GET", "url": "https://api.test/users", "headers": [] },
                    "response": {
                      "status": 200,
                      "headers": [{ "name": "Content-Type", "value": "application/json" }],
                      "content": { "mimeType": "application/json", "text": "{\\"users\\":[]}" }
                    }
                  }
                ]
              }
            }
            """
        let spec = try HARImporter.parse(har)
        let endpoint = try #require(spec.endpoints.first)
        #expect(endpoint.method == "GET")
        #expect(endpoint.path == "/users")
        #expect(endpoint.responses.count == 1)
        #expect(endpoint.responses[0].name == "default")
        #expect(endpoint.responses[0].body?.contains("users") == true)
    }

    @Test("Parses request cookies from HAR cookies and the Cookie header as presence-only rules")
    func parsesRequestCookies() throws {
        let har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET", "url": "https://api.test/users", "headers": [],
                      "cookies": [{ "name": "session_id", "value": "abc123" }]
                    },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  },
                  {
                    "request": {
                      "method": "GET", "url": "https://api.test/profile",
                      "headers": [{ "name": "Cookie", "value": "theme=dark; locale=en-US" }]
                    },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  }
                ]
              }
            }
            """
        let spec = try HARImporter.parse(har)

        let users = try #require(spec.endpoints.first { $0.path == "/users" })
        #expect(users.cookies.count == 1)
        #expect(users.cookies[0].name == "session_id")
        #expect(users.cookies[0].match == nil)
        #expect(users.cookies[0].required == true)

        let profile = try #require(spec.endpoints.first { $0.path == "/profile" })
        #expect(profile.cookies.count == 2)
        #expect(profile.cookies.first { $0.name == "theme" }?.match == nil)
        #expect(profile.cookies.first { $0.name == "theme" }?.matchType == .equalTo)
    }

    @Test("Parses request query params from queryString and the URL, redacting sensitive ones")
    func parsesQueryParameters() throws {
        let har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET", "url": "https://api.test/search?q=laptop\\u0026sort=popular", "headers": [],
                      "queryString": [
                        { "name": "q", "value": "laptop" },
                        { "name": "sort", "value": "popular" }
                      ]
                    },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  },
                  {
                    "request": { "method": "GET", "url": "https://api.test/search?q=phone\\u0026page=2", "headers": [] },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  }
                ]
              }
            }
            """
        let spec = try HARImporter.parse(har)
        let endpoint = try #require(spec.endpoints.first)
        #expect(endpoint.queryParameters.count == 3)
        #expect(endpoint.queryParameters.first { $0.name == "sort" }?.match == "popular")
        #expect(endpoint.queryParameters.first { $0.name == "page" }?.match == "2")
        #expect(endpoint.queryParameters.first { $0.name == "q" }?.match == nil)
        #expect(endpoint.queryParameters.first { $0.name == "q" }?.required == true)
        #expect(endpoint.queryParameters.first { $0.name == "sort" }?.matchType == .equalTo)
    }

    @Test("An API key query parameter imports as presence only; an ordinary keyword keeps its value")
    func apiKeyQueryIsNotCommitted() throws {
        let har = """
            { "log": { "version": "1.2", "entries": [ {
              "request": { "method": "GET", "url": "https://api.test/search?api_key=real-key\\u0026keyword=shoes\\u0026sig=s1",
                           "headers": [] },
              "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
            } ] } }
            """
        let names = CredentialNames(extraQueryParameters: ["sig"])
        let spec = try HARImporter.parse(har, credentialNames: names)
        let rules = try #require(spec.endpoints.first).queryParameters
        #expect(rules.first { $0.name == "api_key" }?.match == nil)
        #expect(rules.first { $0.name == "sig" }?.match == nil)
        #expect(rules.first { $0.name == "keyword" }?.match == "shoes")
        #expect(!String(describing: spec).contains("real-key"))

        let exempt = try HARImporter.parse(har, credentialNames: CredentialNames(exceptQueryParameters: ["api_key"]))
        #expect(exempt.endpoints.first?.queryParameters.first { $0.name == "api_key" }?.match == "real-key")
    }

    @Test("Redacts sensitive query parameters, passes non-sensitive through")
    func redactsSensitiveQueryParameters() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": {
                    "method": "GET",
                    "url": "https://api.test/callback?code=x\\u0026state=y\\u0026redirect_uri=/home",
                    "headers": [],
                    "queryString": [
                      { "name": "code", "value": "auth-code-xyz" },
                      { "name": "state", "value": "csrf-state" },
                      { "name": "redirect_uri", "value": "/home" }
                    ]
                  },
                  "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let endpoint = try #require(spec.endpoints.first)
        #expect(endpoint.queryParameters.first { $0.name == "code" }?.match == nil)
        #expect(endpoint.queryParameters.first { $0.name == "state" }?.match == nil)
        #expect(endpoint.queryParameters.first { $0.name == "code" }?.required == true)
        #expect(endpoint.queryParameters.first { $0.name == "redirect_uri" }?.match == "/home")
    }

    @Test("Redacts sensitive response headers, passes non-sensitive through")
    func redactsSensitiveResponseHeaders() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/secure", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [
                      { "name": "Set-Cookie", "value": "session=s3cr3t; HttpOnly" },
                      { "name": "Content-Type", "value": "application/json" }
                    ],
                    "content": { "mimeType": "application/json", "text": "{\\"ok\\":true}" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let response = try #require(spec.endpoints.first?.responses.first)
        #expect(response.headers["Set-Cookie"] == "[redacted]")
        #expect(response.headers["Content-Type"] == "application/json")
    }

    @Test("Redacts the JWT signature in a header value, preserving header and payload")
    func redactsJWTSignature() throws {
        let jwtHeader = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
        let jwtPayload = "eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoxNjAwMDAwMDAwfQ"
        let jwtSignature = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        let fullJWT = "\(jwtHeader).\(jwtPayload).\(jwtSignature)"
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/resource", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [{ "name": "X-Correlation", "value": "\(fullJWT)" }],
                    "content": { "mimeType": "application/json", "text": "{}" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let response = try #require(spec.endpoints.first?.responses.first)
        #expect(response.headers["X-Correlation"] == "\(jwtHeader).\(jwtPayload).redacted")
    }

    @Test("Infers presence-only header rules from the headers every successful call sent")
    func infersHeaderRules() throws {
        func entry(_ path: String, status: Int, headers: String) -> String {
            """
            {
              "request": { "method": "GET", "url": "https://api.test\(path)", "headers": [\(headers)] },
              "response": { "status": \(status), "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
            }
            """
        }
        let authed = #"""
            { "name": "Authorization", "value": "Bearer secret-token" },
            { "name": "X-Client-Version", "value": "1.2" },
            { "name": "Accept", "value": "application/json" },
            { "name": "User-Agent", "value": "app" },
            { "name": ":authority", "value": "api.test" }
            """#
        let har = """
            { "log": { "version": "1.2", "entries": [
              \(entry("/v1/me", status: 200, headers: authed)),
              \(entry("/v1/me", status: 200, headers: #"{ "name": "authorization", "value": "Bearer other" }, { "name": "X-Client-Version", "value": "1.3" }, { "name": "X-Trace", "value": "t" }"#)),
              \(entry("/v1/me", status: 401, headers: #"{ "name": "X-Client-Version", "value": "1.2" }"#)),
              \(entry("/content/1", status: 200, headers: #"{ "name": "Accept", "value": "*/*" }"#))
            ] } }
            """
        let spec = try HARImporter.parse(har, inferHeaderRules: true)
        let me = try #require(spec.endpoints.first { $0.path == "/v1/me" })
        #expect(me.requiredHeaders == ["Authorization", "X-Client-Version"])
        #expect(spec.endpoints.first { $0.path == "/content/1" }?.requiredHeaders == [])

        let project = ImportConverter.convert(spec, selection: .all, name: "Test", path: "/tmp/test.moqproj")
        let rules = try #require(project.endpoints.first { $0.path == "/v1/me" }?.requestRules?.headers)
        #expect(rules.map(\.name) == ["Authorization", "X-Client-Version"])
        #expect(rules.allSatisfy { $0.match == nil && $0.required == true })

        #expect(try HARImporter.parse(har).endpoints.allSatisfy { $0.requiredHeaders.isEmpty })
    }

    @Test("Redacts custom auth headers by name keyword and by --redact-header")
    func redactsCustomAuthHeaders() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/secure", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [
                      { "name": "X-Firebase-AppCheck", "value": "appcheck-raw" },
                      { "name": "X-Refresh-Token", "value": "refresh-raw" },
                      { "name": "X-App-Device", "value": "device-raw" },
                      { "name": "Access-Control-Allow-Credentials", "value": "true" },
                      { "name": "Cache-Control", "value": "no-store" }
                    ],
                    "content": { "mimeType": "application/json", "text": "{}" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har, redactHeaders: ["x-app-device"])
        let headers = try #require(spec.endpoints.first?.responses.first?.headers)
        #expect(headers["X-Firebase-AppCheck"] == "[redacted]")
        #expect(headers["X-Refresh-Token"] == "[redacted]")
        #expect(headers["X-App-Device"] == "[redacted]")
        #expect(headers["Access-Control-Allow-Credentials"] == "true")
        #expect(headers["Cache-Control"] == "no-store")

        let unconfigured = try HARImporter.parse(har)
        #expect(unconfigured.endpoints.first?.responses.first?.headers["X-App-Device"] == "device-raw")
    }

    @Test("Redacts sensitive fields in a JSON response body and warns about the heuristic")
    func redactsSensitiveJSONBody() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/session", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [],
                    "content": {
                      "mimeType": "application/json",
                      "text": "{\\"token\\":\\"secret\\",\\"profile\\":{\\"email\\":\\"person@example.com\\",\\"name\\":\\"Sam\\"}}"
                    }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let body = try #require(spec.endpoints.first?.responses.first?.body)
        #expect(!body.contains("secret"))
        #expect(!body.contains("person@example.com"))
        #expect(body.contains("[redacted]"))
        #expect(spec.warnings.first?.contains("heuristically") == true)
    }

    @Test("Preserves duplicate exchanges with the same status as separate responses")
    func preservesDuplicateExchanges() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/items", "headers": [] },
                  "response": {
                    "status": 200, "headers": [{ "name": "Content-Type", "value": "application/json" }],
                    "content": { "mimeType": "application/json", "text": "{\\"ok\\":true}" }
                  }
                },
                {
                  "request": { "method": "GET", "url": "https://api.test/items", "headers": [] },
                  "response": {
                    "status": 200, "headers": [{ "name": "Content-Type", "value": "application/json" }],
                    "content": { "mimeType": "application/json", "text": "{\\"ok\\":true}" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let endpoint = try #require(spec.endpoints.first)
        #expect(endpoint.responses.map(\.name) == ["default", "success-200"])
        #expect(endpoint.responses.map(\.statusCode) == [200, 200])
    }

    @Test("Skips malformed entries and surfaces a warning instead of failing")
    func skipsMalformedEntries() throws {
        let har = """
            {
              "log": {
                "version": "1.2",
                "creator": { "name": "Proxy" },
                "entries": [
                  {
                    "request": { "method": null, "url": "https://api.test/ignored", "headers": [] },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  },
                  {
                    "request": { "method": "GET", "url": "https://api.test/users", "headers": [] },
                    "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                  }
                ]
              }
            }
            """
        let spec = try HARImporter.parse(har)
        #expect(spec.endpoints.count == 1)
        #expect(spec.warnings == ["Skipped HAR entry 1: missing request method."])
        #expect(spec.endpoints.first?.path == "/users")
    }

    @Test("Throws a descriptive error when no entries are importable")
    func throwsWhenNoImportableEntries() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": null, "url": "https://api.test/ignored", "headers": [] },
                  "response": { "status": 200, "headers": [], "content": { "mimeType": "application/json", "text": "{}" } }
                }
              ] }
            }
            """
        #expect(throws: HARImportError.self) {
            try HARImporter.parse(har)
        }
    }

    @Test("Throws when the HAR file has zero entries")
    func throwsWhenZeroEntries() throws {
        let har = #"{ "log": { "version": "1.2", "entries": [] } }"#
        #expect(throws: HARImportError.self) {
            try HARImporter.parse(har)
        }
    }

    /// Regression test: a binary response (base64-encoded, non-textual MIME type) used to be
    /// returned as plain text with no way to tell the caller it was base64 — ImportConverter then
    /// ran it through parseBody, which happily treated the base64 as a literal string and built a
    /// variant with no body_encoding. InlineBody.resolve later served that literal base64 text
    /// as the response body instead of the decoded image bytes it was supposed to be.
    @Test("A binary response body is marked isBase64, not silently returned as literal text")
    func binaryResponseBodyIsMarkedBase64() throws {
        let pngBase64 = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]).base64EncodedString()
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/logo.png", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [],
                    "content": { "mimeType": "image/png", "text": "\(pngBase64)", "encoding": "base64" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let response = try #require(spec.endpoints.first?.responses.first)
        #expect(response.isBase64)
        #expect(response.body == pngBase64)
    }

    @Test("A textual base64-encoded response is decoded, not marked isBase64")
    func textualBase64ResponseIsDecoded() throws {
        let encoded = Data("{\"ok\":true}".utf8).base64EncodedString()
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/data", "headers": [] },
                  "response": {
                    "status": 200,
                    "headers": [],
                    "content": { "mimeType": "application/json", "text": "\(encoded)", "encoding": "base64" }
                  }
                }
              ] }
            }
            """
        let spec = try HARImporter.parse(har)
        let response = try #require(spec.endpoints.first?.responses.first)
        #expect(!response.isBase64)
        #expect(response.body?.contains("ok") == true)
    }
}
