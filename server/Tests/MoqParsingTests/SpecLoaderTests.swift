import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

@Suite("SpecLoader Tests")
struct SpecLoaderTests {
    let loader = SpecLoader()

    @Test("Loads data from existing file")
    func loadFromFile() throws {
        let url = Bundle.module.url(forResource: "petstore", withExtension: "yaml", subdirectory: "Fixtures")!
        let data = try loader.loadData(from: url.path)
        #expect(!data.isEmpty)
    }

    @Test("Throws fileNotFound for missing file")
    func missingFile() throws {
        do {
            _ = try loader.loadData(from: "/nonexistent/path/file.yaml")
            Issue.record("Expected error")
        } catch let error as SpecLoaderError {
            #expect(error.description.contains("File not found"))
        }
    }

    @Test("Throws invalidURL for malformed URL")
    func invalidURL() throws {
        // A URL string with spaces is invalid
        do {
            _ = try loader.loadData(from: "http://not a valid url with spaces")
            Issue.record("Expected error")
        } catch let error as SpecLoaderError {
            #expect(error.description.contains("Invalid URL"))
        }
    }

    @Test("Handles tilde path expansion")
    func tildePath() throws {
        // This should expand ~ and then fail with file not found (not crash)
        do {
            _ = try loader.loadData(from: "~/nonexistent-moqserver-test-file.yaml")
            Issue.record("Expected error")
        } catch let error as SpecLoaderError {
            #expect(error.description.contains("File not found"))
        }
    }

    @Test("SpecLoaderError descriptions")
    func errorDescriptions() {
        let invalidURL = SpecLoaderError.invalidURL("bad-url")
        #expect(invalidURL.description == "Invalid URL: bad-url")

        let notFound = SpecLoaderError.fileNotFound("/path/to/file")
        #expect(notFound.description == "File not found: /path/to/file")
    }
}

@Suite("ParsedSpecLoader Tests")
struct ParsedSpecLoaderTests {
    @Test("Explicit openapi format delegates to OpenAPI parser")
    func explicitOpenAPIFormat() throws {
        let data = Bundle.module.url(forResource: "petstore", withExtension: "yaml", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: "petstore.yaml", format: .openapi)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Explicit har format delegates to HAR parser")
    func explicitHARFormat() throws {
        let data = Bundle.module.url(forResource: "sample", withExtension: "har", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: "sample.har", format: .har)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Auto format with .yaml extension uses OpenAPI parser")
    func autoYAML() throws {
        let data = Bundle.module.url(forResource: "petstore", withExtension: "yaml", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: "petstore.yaml", format: .auto)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Auto format with .har extension uses HAR parser")
    func autoHAR() throws {
        let data = Bundle.module.url(forResource: "sample", withExtension: "har", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: "test.har", format: .auto)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Auto format with no source auto-detects OpenAPI")
    func autoDetectOpenAPI() throws {
        let data = Bundle.module.url(forResource: "petstore", withExtension: "yaml", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: nil, format: .auto)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Auto format with no source auto-detects HAR JSON")
    func autoDetectHAR() throws {
        let data = Bundle.module.url(forResource: "sample", withExtension: "har", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: nil, format: .auto)
        #expect(!spec.endpoints.isEmpty)
    }

    @Test("Auto format with garbage data throws unsupportedInput")
    func autoGarbage() {
        let data = Data("this is neither openapi nor har".utf8)
        let loader = ParsedSpecLoader()
        do {
            _ = try loader.parse(data: data, source: nil, format: .auto)
            Issue.record("Expected error")
        } catch let error as ParsedSpecLoaderError {
            #expect(error.description.contains("Could not detect"))
        } catch {
            Issue.record("Wrong error type: \(error)")
        }
    }

    @Test("ParsedSpecLoaderError description")
    func errorDescription() {
        let error = ParsedSpecLoaderError.unsupportedInput
        #expect(error.description.contains("Could not detect"))
    }

    @Test("SpecInputFormat raw values")
    func formatRawValues() {
        #expect(SpecInputFormat.auto.rawValue == "auto")
        #expect(SpecInputFormat.openapi.rawValue == "openapi")
        #expect(SpecInputFormat.har.rawValue == "har")
    }

    @Test("Auto format with URL source extracts extension")
    func autoURLSource() throws {
        let data = Bundle.module.url(forResource: "petstore", withExtension: "yaml", subdirectory: "Fixtures")
            .flatMap { try? Data(contentsOf: $0) }!
        let loader = ParsedSpecLoader()
        let spec = try loader.parse(data: data, source: "https://example.com/specs/petstore.yaml", format: .auto)
        #expect(!spec.endpoints.isEmpty)
    }
}
