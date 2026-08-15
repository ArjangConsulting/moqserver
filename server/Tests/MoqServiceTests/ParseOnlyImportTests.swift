import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport
@testable import MoqService

/// `parseHAR`/`parseOpenAPI`: parse without merging into any session or touching disk beyond
/// reading the source. The counterpart Studio's interactive import review needs — hold the
/// parsed result in memory, let the user accept/reject/rename/AI-generate per entry, then persist
/// the whole thing in one `writeProject` call at the end (see MoqService.writeProject).
struct ParseOnlyImportTests {
    let service = MoqService(allowNetworkImport: false)

    func tempFile(_ label: String, contents: String) throws -> String {
        let path = (NSTemporaryDirectory() as NSString).appendingPathComponent(
            "parse-only-\(label)-\(UUID().uuidString)")
        try contents.write(toFile: path, atomically: true, encoding: .utf8)
        return path
    }

    @Test("parseHAR returns the parsed spec without a session or any project mutation")
    func parseHARIsStateless() throws {
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/users", "headers": [] },
                  "response": {
                    "status": 200, "headers": [],
                    "content": { "mimeType": "application/json", "text": "{\\"users\\":[]}" }
                  }
                }
              ] }
            }
            """
        let path = try tempFile("basic.har", contents: har)
        defer { try? FileManager.default.removeItem(atPath: path) }

        let spec = try service.parseHAR(path: path)
        #expect(spec.endpoints.count == 1)
        #expect(spec.endpoints.first?.path == "/users")
    }

    @Test("parseHAR surfaces a missing file as a catchable error, not a crash")
    func parseHARMissingFile() {
        #expect(throws: MoqServiceError.self) {
            _ = try service.parseHAR(path: "/tmp/does-not-exist-\(UUID().uuidString).har")
        }
    }

    @Test("parseOpenAPI returns the parsed spec and the unchanged local path as resolvedSource")
    func parseOpenAPIFromLocalFile() async throws {
        let spec = """
            openapi: 3.0.3
            info:
              title: Sample API
              version: "1.0"
            paths:
              /users:
                get:
                  responses:
                    "200":
                      description: OK
            """
        let path = try tempFile("basic.yaml", contents: spec)
        defer { try? FileManager.default.removeItem(atPath: path) }

        let result = try await service.parseOpenAPI(source: path, auth: nil)
        #expect(result.spec.title == "Sample API")
        #expect(result.resolvedSource == path)
    }

    @Test("parseOpenAPI refuses a URL source when network import is disabled")
    func parseOpenAPINetworkDisabled() async throws {
        await #expect(throws: MoqServiceError.self) {
            _ = try await service.parseOpenAPI(source: "https://example.invalid/openapi.json", auth: nil)
        }
    }

    @Test("A binary HAR response round-trips through parseHAR as isBase64, not decoded text")
    func parseHARPreservesBase64Flag() throws {
        let pngBase64 = Data([0x89, 0x50, 0x4E, 0x47]).base64EncodedString()
        let har = """
            {
              "log": { "version": "1.2", "entries": [
                {
                  "request": { "method": "GET", "url": "https://api.test/logo.png", "headers": [] },
                  "response": {
                    "status": 200, "headers": [],
                    "content": { "mimeType": "image/png", "text": "\(pngBase64)", "encoding": "base64" }
                  }
                }
              ] }
            }
            """
        let path = try tempFile("binary.har", contents: har)
        defer { try? FileManager.default.removeItem(atPath: path) }

        let spec = try service.parseHAR(path: path)
        let response = try #require(spec.endpoints.first?.responses.first)
        #expect(response.isBase64)
        #expect(response.body == pngBase64)
    }
}
