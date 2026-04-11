import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

struct HARParserTests {
    let parser = HARParser()

    func loadFixture(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    @Test("Parses sample HAR file")
    func parseSampleHAR() throws {
        let data = try loadFixture("sample.har")
        let spec = try parser.parse(data: data)

        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Normalizes HAR edge cases and deduplicates identical exchanges")
    func normalizesHarEdgeCases() throws {
        let longBody = String(repeating: "x", count: 1200)
        let base64Body = Data([0x48, 0x49]).base64EncodedString()
        let har = """
        {
          "log": {
            "version": "1.2",
            "entries": [
              {
                "request": {
                  "method": "get",
                  "url": "https://example.com",
                  "queryString": [],
                  "postData": {
                    "mimeType": "application/json",
                    "text": "   \(longBody)   "
                  }
                },
                "response": {
                  "status": 700,
                  "headers": [],
                  "content": {
                    "mimeType": "application/octet-stream",
                    "text": "\(base64Body)",
                    "encoding": "base64"
                  }
                }
              },
              {
                "request": {
                  "method": "get",
                  "url": "https://example.com",
                  "queryString": [],
                  "postData": {
                    "mimeType": "application/json",
                    "text": "   \(longBody)   "
                  }
                },
                "response": {
                  "status": 700,
                  "headers": [],
                  "content": {
                    "mimeType": "application/octet-stream",
                    "text": "\(base64Body)",
                    "encoding": "base64"
                  }
                }
              },
              {
                "request": {
                  "method": "get",
                  "url": "https://example.com/api/redirect?mode=full",
                  "postData": {
                    "text": ""
                  }
                },
                "response": {
                  "status": 302,
                  "headers": [{ "name": "X-Trace", "value": "abc" }],
                  "content": {
                    "mimeType": "text/plain"
                  }
                }
              },
              {
                "request": {
                  "method": "post",
                  "url": "https://example.com/api/error",
                  "queryString": [{ "name": "", "value": "ignored" }],
                  "postData": {
                    "mimeType": "text/plain",
                    "text": "boom"
                  }
                },
                "response": {
                  "status": 503,
                  "headers": [{ "name": "Content-Type", "value": "application/json" }],
                  "content": {
                    "text": "{\\"error\\":true}"
                  }
                }
              }
            ]
          }
        }
        """.data(using: .utf8)!

        let spec = try parser.parse(data: har)

        #expect(spec.title == "HAR Import")
        #expect(spec.version == "1.2")

        let root = try #require(spec.endpoints.first { $0.method == "GET" && $0.path == "/" })
        #expect(root.responses.count == 1)
        #expect(root.responses.first?.statusCode == 200)
        #expect(root.responses.first?.name == "default")
        #expect(root.responses.first?.headers.contains { $0.0 == "Content-Type" && $0.1 == "application/octet-stream" } == true)
        #expect(root.responses.first?.body == Data([0x48, 0x49]))
        #expect(root.responses.first?.requestMatch?.headers == ["Content-Type": "application/json"])
        #expect(root.responses.first?.requestMatch?.bodyContains?.count == 1024)

        let redirect = try #require(spec.endpoints.first { $0.path == "/api/redirect" })
        #expect(redirect.responses.first?.statusCode == 302)
        #expect(redirect.responses.first?.name == "redirect-302")
        #expect(redirect.responses.first?.requestMatch?.query == ["mode": "full"])
        #expect(redirect.responses.first?.body == nil)

        let error = try #require(spec.endpoints.first { $0.method == "POST" && $0.path == "/api/error" })
        #expect(error.responses.first?.statusCode == 503)
        #expect(error.responses.first?.name == "error-503")
        #expect(error.responses.first?.requestMatch?.headers == ["Content-Type": "text/plain"])
        #expect(error.responses.first?.requestMatch?.query.isEmpty == true)
    }

    @Test("Rejects HAR files with no importable entries")
    func rejectsHarWithoutImportableEntries() {
        let har = """
        {
          "log": {
            "version": "1.2",
            "creator": { "name": "Browser", "version": "1.0" },
            "entries": []
          }
        }
        """.data(using: .utf8)!

        #expect(throws: HARParserError.noEntries) {
            _ = try parser.parse(data: har)
        }

        #expect(HARParserError.noEntries.description == "HAR file does not contain any importable HTTP entries.")
    }
}
