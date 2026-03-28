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

    @Test("Loads mock files from directory")
    func loadMockFiles() throws {
        let dir = try fixtureDirectory()
        let loader = MockFileLoader()
        let endpoints = try loader.load(from: dir)

        #expect(!endpoints.isEmpty)
    }
}
