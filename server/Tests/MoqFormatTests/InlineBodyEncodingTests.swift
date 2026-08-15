import Foundation
import Testing

@testable import MoqCore
@testable import MoqFormat

/// Covers `body_encoding` and the single inline-body resolution path shared by fixture
/// materialization and runtime conversion.
struct InlineBodyTests {
    @Test("String body resolves to raw UTF-8, never JSON-quoted")
    func stringBodyIsRawBytes() throws {
        let resolved = try InlineBody.resolve(.string("plain text"), encoding: nil)
        #expect(String(data: resolved.data, encoding: .utf8) == "plain text")
        #expect(resolved.fileExtension == "txt")
    }

    @Test("Structured body resolves to JSON")
    func structuredBodyIsJSON() throws {
        let resolved = try InlineBody.resolve(.object(["a": .int(1)]), encoding: nil)
        #expect(String(data: resolved.data, encoding: .utf8) == #"{"a":1}"#)
        #expect(resolved.fileExtension == "json")
    }

    @Test("base64 body decodes to arbitrary bytes")
    func base64BodyDecodes() throws {
        // PNG magic number — bytes that are not valid UTF-8 text.
        let raw = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let resolved = try InlineBody.resolve(.string(raw.base64EncodedString()), encoding: .base64)
        #expect(resolved.data == raw)
        #expect(resolved.fileExtension == "bin")
    }

    @Test(
        "Content-Type picks the fixture extension without changing the bytes",
        arguments: [
            ("image/png", "png"),
            ("image/jpeg", "jpg"),
            ("application/xml", "xml"),
            ("application/vnd.acme+json", "json"),
            ("text/plain; charset=utf-8", "txt"),
            ("application/zip", "zip"),
        ]
    )
    func contentTypeDrivesExtension(contentType: String, expected: String) throws {
        let raw = Data([0x01, 0x02, 0x03])
        let resolved = try InlineBody.resolve(
            .string(raw.base64EncodedString()), encoding: .base64, contentType: contentType)
        #expect(resolved.fileExtension == expected)
        #expect(resolved.data == raw)
    }

    @Test("base64 body tolerates the line wrapping YAML block scalars introduce")
    func base64ToleratesWrapping() throws {
        let raw = Data(repeating: 0xAB, count: 120)
        let wrapped = raw.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        let resolved = try InlineBody.resolve(.string(wrapped), encoding: .base64)
        #expect(resolved.data == raw)
    }

    @Test("base64 encoding on a non-string body is rejected")
    func base64RequiresString() {
        #expect(throws: InlineBodyError.encodingRequiresStringBody(.base64)) {
            try InlineBody.resolve(.object(["a": .int(1)]), encoding: .base64)
        }
    }

    @Test("Undecodable base64 is rejected")
    func invalidBase64Rejected() {
        #expect(throws: InlineBodyError.invalidBase64) {
            // Valid base64 alphabet but a length that cannot decode.
            try InlineBody.resolve(.string("abcde"), encoding: .base64)
        }
    }
}

struct BodyEncodingValidationTests {
    let validator = ProjectValidator()

    func project(variant: ProjectVariant) -> MoqProject {
        MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: "Test",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            endpoints: [EndpointDocument(id: "e", method: "GET", path: "/t", variants: [variant])],
            projectPath: "/tmp/test.moqproj"
        )
    }

    func errorCodes(_ variant: ProjectVariant) -> [DiagnosticCode] {
        validator.validate(project(variant: variant))
            .filter { $0.severity == .error }
            .compactMap(\.code)
    }

    @Test("Valid base64 body passes")
    func validBase64Passes() {
        let variant = ProjectVariant(
            name: "v", status: 200, body: .string("aGVsbG8="), bodyEncoding: .base64)
        #expect(errorCodes(variant).isEmpty)
    }

    @Test("body_encoding without a body is an error")
    func encodingWithoutBody() {
        let variant = ProjectVariant(name: "v", status: 200, bodyEncoding: .base64)
        #expect(errorCodes(variant).contains(.bodyEncodingWithoutBody))
    }

    @Test("body_encoding alongside body_file is an error")
    func encodingWithBodyFile() {
        let variant = ProjectVariant(
            name: "v", status: 200, bodyEncoding: .base64, bodyFile: "fixtures/responses/a.bin")
        #expect(errorCodes(variant).contains(.bodyEncodingOnBodyFile))
    }

    @Test("base64 on a structured body is an error")
    func encodingRequiresString() {
        let variant = ProjectVariant(
            name: "v", status: 200, body: .object(["a": .int(1)]), bodyEncoding: .base64)
        #expect(errorCodes(variant).contains(.bodyEncodingRequiresString))
    }

    @Test("Undecodable base64 body is an error")
    func invalidBase64() {
        let variant = ProjectVariant(
            name: "v", status: 200, body: .string("!!!not base64!!!"), bodyEncoding: .base64)
        #expect(errorCodes(variant).contains(.invalidBase64Body))
    }

    @Test("utf8 encoding is accepted on a string body")
    func utf8OnStringPasses() {
        let variant = ProjectVariant(
            name: "v", status: 200, body: .string("hello"), bodyEncoding: .utf8)
        #expect(errorCodes(variant).isEmpty)
    }
}

struct BodyEncodingRoundTripTests {
    /// The divergence that motivated `InlineBody`: the store wrote a string body as raw UTF-8
    /// while the runtime served it JSON-quoted. Both now resolve through one path.
    @Test("Store and runtime agree on the bytes for a string body")
    func storeAndRuntimeAgreeOnStringBody() async throws {
        let path = NSTemporaryDirectory() + "inline-body-\(UUID().uuidString).moqproj"
        defer { try? FileManager.default.removeItem(atPath: path) }

        let store = try ProjectStore.create(
            manifest: ProjectManifest(
                version: "1",
                name: "RoundTrip",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            at: path
        )
        let variant = ProjectVariant(
            name: "default",
            status: 200,
            headers: ["Content-Type": "text/plain"],
            body: .string("plain text")
        )
        try await store.addEndpoint(
            EndpointDocument(id: "e", method: "GET", path: "/t", variants: [variant]))
        try await store.save()

        // The fixture on disk holds the literal text, not a JSON-quoted string.
        let fixtures = try FileManager.default.subpathsOfDirectory(atPath: path + "/fixtures")
            .filter { $0.hasSuffix(".txt") }
        let fixture = try #require(fixtures.first)
        let onDisk = try String(contentsOfFile: path + "/fixtures/" + fixture, encoding: .utf8)
        #expect(onDisk == "plain text")

        // And the runtime resolves the same bytes from the inline body.
        let served = try InlineBody.resolve(.string("plain text"), encoding: nil).data
        #expect(served == Data(onDisk.utf8))
    }

    @Test("A base64 body materializes as decoded bytes in a content-type-named fixture")
    func base64MaterializesAsBinary() async throws {
        let path = NSTemporaryDirectory() + "inline-b64-\(UUID().uuidString).moqproj"
        defer { try? FileManager.default.removeItem(atPath: path) }

        let store = try ProjectStore.create(
            manifest: ProjectManifest(
                version: "1",
                name: "Binary",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            at: path
        )
        let raw = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        try await store.addEndpoint(
            EndpointDocument(
                id: "img", method: "GET", path: "/img",
                variants: [
                    ProjectVariant(
                        name: "default",
                        status: 200,
                        headers: ["Content-Type": "image/png"],
                        body: .string(raw.base64EncodedString()),
                        bodyEncoding: .base64
                    )
                ]))
        try await store.save()

        let fixtures = try FileManager.default.subpathsOfDirectory(atPath: path + "/fixtures")
            .filter { $0.hasSuffix(".png") }
        let fixture = try #require(fixtures.first)
        let onDisk = try Data(contentsOf: URL(fileURLWithPath: path + "/fixtures/" + fixture))
        #expect(onDisk == raw)
    }
}
