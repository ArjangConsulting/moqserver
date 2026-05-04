import Foundation
import Testing
@testable import MoqRuntime

struct ConfigTests {
    func loadFixture(_ name: String) throws -> String {
        let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures")!
        return url.path
    }

    @Test("Loads config from YAML file")
    func loadYAMLConfig() throws {
        let path = try loadFixture("config.yaml")
        let loader = ConfigLoader()
        let config = try loader.load(from: path)

        #expect(config.auth?.oauth2Tokens == ["mock-oauth-token"])
        #expect(config.auth?.oauth2Clients?.first?.clientId == "test-client")
        #expect(config.auth?.oauth2Clients?.first?.clientSecret == "test-secret")
    }

    @Test("YAML config loads all fields correctly")
    func yamlConfigAllFields() throws {
        let path = try loadFixture("config.yaml")
        let loader = ConfigLoader()
        let config = try loader.load(from: path)

        #expect(config.variantOverrides?["GET /pets"] == "error-500")
        #expect(config.globalDelay == 0.0)
        #expect(config.delayOverrides?["POST /pets"] == 0.0)
        #expect(config.auth?.bearerTokens == ["test-token"])
        #expect(config.auth?.basicCredentials?.first?.username == "admin")
        #expect(config.auth?.basicCredentials?.first?.password == "pass")
        #expect(config.auth?.apiKeys?["X-API-Key"] == "my-key")
    }

    @Test("Loads config from JSON file")
    func loadJSONConfig() throws {
        // Write a temp JSON config
        let tempPath = NSTemporaryDirectory() + "moqserver-config-\(UUID().uuidString).json"
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        let json = """
        {
            "globalDelay": 0.5,
            "variantOverrides": {"GET /users": "error-404"}
        }
        """
        try json.data(using: .utf8)!.write(to: URL(fileURLWithPath: tempPath))

        let loader = ConfigLoader()
        let config = try loader.load(from: tempPath)
        #expect(config.globalDelay == 0.5)
        #expect(config.variantOverrides?["GET /users"] == "error-404")
    }

    @Test("Throws fileNotFound for missing file")
    func missingFile() {
        let loader = ConfigLoader()
        do {
            _ = try loader.load(from: "/nonexistent/config.yaml")
            Issue.record("Expected error")
        } catch let error as ConfigLoaderError {
            #expect(error.description.contains("Config file not found"))
        } catch {
            Issue.record("Wrong error type: \(error)")
        }
    }

    @Test("ConfigLoaderError description")
    func errorDescription() {
        let error = ConfigLoaderError.fileNotFound("/path/config.yaml")
        #expect(error.description == "Config file not found: /path/config.yaml")
    }

    @Test("Throws when file contains invalid data")
    func invalidData() throws {
        let tempPath = NSTemporaryDirectory() + "moqserver-bad-config-\(UUID().uuidString).txt"
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        try "this is not yaml or json config @@@ !!!".data(using: .utf8)!.write(to: URL(fileURLWithPath: tempPath))

        let loader = ConfigLoader()
        #expect(throws: (any Error).self) {
            _ = try loader.load(from: tempPath)
        }
    }

    @Test("ServerConfig helper methods")
    func serverConfigHelpers() {
        let config = ServerConfig(
            variantOverrides: ["GET /pets": "error-500"],
            globalDelay: 1.0,
            delayOverrides: ["POST /pets": 2.0]
        )
        #expect(config.variantOverride(for: "GET /pets") == "error-500")
        #expect(config.variantOverride(for: "GET /unknown") == nil)
        #expect(config.effectiveDelay(for: "POST /pets") == 2.0)
        #expect(config.effectiveDelay(for: "GET /unknown") == 1.0)
    }
}
