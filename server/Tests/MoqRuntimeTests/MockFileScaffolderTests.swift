import Foundation
import Testing
@testable import MoqCore
@testable import MoqRuntime

struct MockFileScaffolderTests {
    private func tempDir() -> String {
        let dir = NSTemporaryDirectory() + "moqserver-scaffold-\(UUID().uuidString)"
        return dir
    }

    @Test("Scaffolds single endpoint with default response")
    func scaffoldSingleEndpoint() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/pets",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data(#"{"id":1}"#.utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        let count = try MockFileScaffolder.scaffold(from: spec, output: output)
        #expect(count == 1)

        let filePath = output + "/pets/GET.json"
        #expect(FileManager.default.fileExists(atPath: filePath))

        let data = try Data(contentsOf: URL(fileURLWithPath: filePath))
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(json?["id"] as? Int == 1)
    }

    @Test("Scaffolds multiple variants with named files")
    func scaffoldMultipleVariants() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/users",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data("[]".utf8)),
                        ParsedResponse(name: "error-404", statusCode: 404, headers: [], body: Data(#"{"error":"not found"}"#.utf8)),
                        ParsedResponse(name: "error-500", statusCode: 500, headers: [], body: nil),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        let count = try MockFileScaffolder.scaffold(from: spec, output: output)
        #expect(count == 3)

        #expect(FileManager.default.fileExists(atPath: output + "/users/GET.json"))
        #expect(FileManager.default.fileExists(atPath: output + "/users/GET.error-404.json"))
        #expect(FileManager.default.fileExists(atPath: output + "/users/GET.error-500.json"))
    }

    @Test("Creates meta file for non-default status code")
    func metaForCustomStatus() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "POST",
                    path: "/items",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 201, headers: [], body: Data("{}".utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        let count = try MockFileScaffolder.scaffold(from: spec, output: output)
        #expect(count == 1)

        // Status 201 != inferred 200 for "default", so meta should exist
        let metaPath = output + "/items/POST.meta.json"
        #expect(FileManager.default.fileExists(atPath: metaPath))

        let metaData = try Data(contentsOf: URL(fileURLWithPath: metaPath))
        let meta = try JSONSerialization.jsonObject(with: metaData) as? [String: Any]
        #expect(meta?["statusCode"] as? Int == 201)
    }

    @Test("No meta file when status matches inferred default")
    func noMetaForDefaultStatus() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/health",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data("{}".utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let metaPath = output + "/health/GET.meta.json"
        #expect(!FileManager.default.fileExists(atPath: metaPath))
    }

    @Test("No meta file for error variant with matching inferred status")
    func noMetaForMatchingErrorStatus() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/data",
                    responses: [
                        ParsedResponse(name: "error-500", statusCode: 500, headers: [], body: Data("{}".utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let metaPath = output + "/data/GET.error-500.meta.json"
        #expect(!FileManager.default.fileExists(atPath: metaPath))
    }

    @Test("Creates meta file when headers present")
    func metaForHeaders() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/xml",
                    responses: [
                        ParsedResponse(
                            name: "default",
                            statusCode: 200,
                            headers: [("Content-Type", "application/xml")],
                            body: Data("<root/>".utf8)
                        ),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let metaPath = output + "/xml/GET.meta.json"
        #expect(FileManager.default.fileExists(atPath: metaPath))
    }

    @Test("Creates meta file when requestMatch present")
    func metaForRequestMatch() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/search",
                    responses: [
                        ParsedResponse(
                            name: "default",
                            statusCode: 200,
                            headers: [],
                            body: Data("{}".utf8),
                            requestMatch: RequestMatch(query: ["q": "test"])
                        ),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let metaPath = output + "/search/GET.meta.json"
        #expect(FileManager.default.fileExists(atPath: metaPath))
    }

    @Test("Pretty-prints JSON bodies")
    func prettyPrintsJSON() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/compact",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data(#"{"a":1,"b":2}"#.utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let data = try Data(contentsOf: URL(fileURLWithPath: output + "/compact/GET.json"))
        let str = String(data: data, encoding: .utf8)!
        // Pretty-printed JSON has newlines
        #expect(str.contains("\n"))
    }

    @Test("Non-JSON body is written as-is")
    func nonJSONBody() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let xmlBody = "<root><item>test</item></root>"
        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/plain",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data(xmlBody.utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let data = try Data(contentsOf: URL(fileURLWithPath: output + "/plain/GET.json"))
        let str = String(data: data, encoding: .utf8)!
        #expect(str == xmlBody)
    }

    @Test("Nil body defaults to empty JSON object")
    func nilBodyDefault() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "DELETE",
                    path: "/items",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: nil),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        _ = try MockFileScaffolder.scaffold(from: spec, output: output)

        let data = try Data(contentsOf: URL(fileURLWithPath: output + "/items/DELETE.json"))
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(json != nil)
        #expect(json?.isEmpty == true)
    }

    @Test("Scaffolds nested path structure")
    func nestedPath() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(
            title: "Test",
            version: "1.0",
            endpoints: [
                ParsedEndpoint(
                    method: "GET",
                    path: "/api/v1/users/{id}/profile",
                    responses: [
                        ParsedResponse(name: "default", statusCode: 200, headers: [], body: Data("{}".utf8)),
                    ],
                    authRequirement: .none
                ),
            ]
        )

        let count = try MockFileScaffolder.scaffold(from: spec, output: output)
        #expect(count == 1)

        let filePath = output + "/api/v1/users/{id}/profile/GET.json"
        #expect(FileManager.default.fileExists(atPath: filePath))
    }

    @Test("Empty spec produces zero files")
    func emptySpec() throws {
        let output = tempDir()
        defer { try? FileManager.default.removeItem(atPath: output) }

        let spec = ParsedSpec(title: "Empty", version: "1.0", endpoints: [])
        let count = try MockFileScaffolder.scaffold(from: spec, output: output)
        #expect(count == 0)
    }
}
