import Foundation
import Testing
@testable import MoqCore

@Suite("AnyCodableValue")
struct AnyCodableValueTests {
    @Test("Decodes scalar values")
    func decodesScalars() throws {
        let decoder = JSONDecoder()

        #expect(try decoder.decode(AnyCodableValue.self, from: Data("null".utf8)) == .null)
        #expect(try decoder.decode(AnyCodableValue.self, from: Data("true".utf8)) == .bool(true))
        #expect(try decoder.decode(AnyCodableValue.self, from: Data("42".utf8)) == .int(42))
        #expect(try decoder.decode(AnyCodableValue.self, from: Data("3.5".utf8)) == .double(3.5))
        #expect(try decoder.decode(AnyCodableValue.self, from: Data("\"hello\"".utf8)) == .string("hello"))
    }

    @Test("Decodes nested arrays and objects")
    func decodesNestedCollections() throws {
        let data = Data(#"{"items":[1,true,{"name":"moq"}],"meta":null}"#.utf8)
        let value = try JSONDecoder().decode(AnyCodableValue.self, from: data)

        #expect(value == .object([
            "items": .array([
                .int(1),
                .bool(true),
                .object(["name": .string("moq")]),
            ]),
            "meta": .null,
        ]))
    }

    @Test("Encodes values back to JSON")
    func encodesRoundTripJSON() throws {
        let value: AnyCodableValue = .object([
            "name": .string("moq"),
            "enabled": .bool(true),
            "count": .int(2),
            "values": .array([.double(1.5), .null]),
        ])

        let data = try JSONEncoder().encode(value)
        let decoded = try JSONDecoder().decode(AnyCodableValue.self, from: data)
        #expect(decoded == value)
    }

    @Test("Converts nested values to Foundation objects")
    func convertsToFoundation() {
        let value: AnyCodableValue = .object([
            "nested": .array([.string("a"), .int(2), .null]),
            "flag": .bool(false),
        ])

        let foundation = value.toFoundation()
        let object = try? #require(foundation as? [String: Any])
        let nested = try? #require(object?["nested"] as? [Any])

        #expect(object?["flag"] as? Bool == false)
        #expect(nested?[0] as? String == "a")
        #expect(nested?[1] as? Int == 2)
        #expect(nested?[2] is NSNull)
    }

    @Test("Serializes objects and scalars to JSON data")
    func serializesToJSONData() throws {
        let objectData = try #require(AnyCodableValue.object(["b": .int(2), "a": .string("x")]).toJSONData(prettyPrinted: true))
        let objectString = try #require(String(data: objectData, encoding: .utf8))
        #expect(objectString.contains("\"a\" : \"x\""))
        #expect(objectString.contains("\"b\" : 2"))

        let scalarData = try #require(AnyCodableValue.string("hello").toJSONData())
        #expect(String(data: scalarData, encoding: .utf8) == "\"hello\"")
    }
}
