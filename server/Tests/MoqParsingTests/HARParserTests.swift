import Foundation
import Testing
@testable import MoqCore
@testable import MoqParsing

@Suite("HARParser")
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
}
