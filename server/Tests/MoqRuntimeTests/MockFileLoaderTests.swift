import Foundation
import Testing
@testable import MoqCore
@testable import MoqRuntime

@Suite("MockFileLoader")
struct MockFileLoaderTests {
    func fixtureDirectory() throws -> String {
        let url = Bundle.module.url(forResource: "mocks", withExtension: nil, subdirectory: "Fixtures")!
        return url.path
    }

    func makeTempMocks(files: [String: String]) throws -> String {
        let root = (NSTemporaryDirectory() as NSString).appendingPathComponent("mock-files-\(UUID().uuidString)")
        try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
        for (relativePath, contents) in files {
            let filePath = (root as NSString).appendingPathComponent(relativePath)
            let directory = (filePath as NSString).deletingLastPathComponent
            try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
            try contents.write(toFile: filePath, atomically: true, encoding: .utf8)
        }
        return root
    }

    @Test("Loads mock files from directory")
    func loadMockFiles() throws {
        let dir = try fixtureDirectory()
        let loader = MockFileLoader()
        let endpoints = try loader.load(from: dir)

        #expect(!endpoints.isEmpty)
    }

    @Test("Parses file paths and rejects unsupported methods")
    func parseFilePath() {
        let parsed = MockFileLoader.parseFilePath("pets/GET.error-500.json")
        #expect(parsed?.method == "GET")
        #expect(parsed?.apiPath == "/pets")
        #expect(parsed?.variantName == "error-500")

        let defaultVariant = MockFileLoader.parseFilePath("pets/POST.json")
        #expect(defaultVariant?.variantName == "default")

        #expect(MockFileLoader.parseFilePath("pets/FETCH.json") == nil)
    }

    @Test("Loads metadata and default content type for variants")
    func loadsMetadataAndDefaultContentType() throws {
        let directory = try makeTempMocks(files: [
            "pets/GET.error-500.json": #"{"error":true}"#,
            "pets/GET.error-500.meta.json": #"{"statusCode":503,"headers":{"X-Trace":"abc"},"delay":0.5,"requestMatch":{"query":{"mode":"full"},"headers":{"X-Role":"admin"},"bodyContains":"boom"}}"#,
            "pets/POST.json": #"{"ok":true}"#,
        ])
        defer { try? FileManager.default.removeItem(atPath: directory) }

        let endpoints = try MockFileLoader().load(from: directory)
        let getPets = try #require(endpoints.first { $0.key.method == .get && $0.key.path == "/pets" })
        let variant = try #require(getPets.variants.first { $0.name == "error-500" })

        #expect(variant.statusCode == .serviceUnavailable)
        #expect(variant.headers.contains { $0.0 == "X-Trace" && $0.1 == "abc" })
        #expect(variant.headers.contains { $0.0 == "Content-Type" && $0.1 == "application/json" })
        #expect(variant.delay == 0.5)
        #expect(variant.requestMatch?.query == ["mode": "full"])
        #expect(variant.requestMatch?.headers == ["X-Role": "admin"])
        #expect(variant.requestMatch?.bodyContains == "boom")
    }

    @Test("Directory not found error describes missing path")
    func directoryNotFoundDescription() {
        do {
            _ = try MockFileLoader().load(from: "/definitely/missing/mocks")
            Issue.record("Expected directoryNotFound error")
        } catch let error as MockFileLoaderError {
            #expect(error.description.contains("Mocks directory not found"))
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }
}
