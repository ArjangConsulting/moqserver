import Foundation
import Testing
@testable import MoqCore
@testable import MoqFormat

@Suite("ProjectWriter - Round-trip")
struct ProjectWriterTests {
    @Test("Round-trip: load, save, reload produces identical project")
    func roundTrip() throws {
        let loader = ProjectLoader()
        let writer = ProjectWriter()

        let sourcePath = Bundle.module.url(
            forResource: "sample-app.moqproj",
            withExtension: nil,
            subdirectory: "Fixtures"
        )!.path
        let original = try loader.load(from: sourcePath)

        // Write to temp directory
        let tempDir = NSTemporaryDirectory()
        let outputPath = (tempDir as NSString).appendingPathComponent("round-trip-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        // Copy fixtures to output (writer doesn't copy fixture files)
        let outputFixtures = (outputPath as NSString).appendingPathComponent("fixtures")
        try FileManager.default.createDirectory(atPath: outputFixtures, withIntermediateDirectories: true)
        let sourceFixtures = (sourcePath as NSString).appendingPathComponent("fixtures")
        for file in try FileManager.default.contentsOfDirectory(atPath: sourceFixtures) {
            let src = (sourceFixtures as NSString).appendingPathComponent(file)
            let dst = (outputFixtures as NSString).appendingPathComponent(file)
            try FileManager.default.copyItem(atPath: src, toPath: dst)
        }

        try writer.write(original, to: outputPath)

        // Reload and compare
        let reloaded = try loader.load(from: outputPath)

        #expect(reloaded.manifest.version == original.manifest.version)
        #expect(reloaded.manifest.name == original.manifest.name)
        #expect(reloaded.manifest.defaults == original.manifest.defaults)
        #expect(reloaded.endpoints.count == original.endpoints.count)

        // Check all endpoint IDs survived
        let originalIds = Set(original.endpoints.map(\.id))
        let reloadedIds = Set(reloaded.endpoints.map(\.id))
        #expect(originalIds == reloadedIds)
    }

    @Test("Deterministic: two saves produce identical output")
    func deterministicSave() throws {
        let loader = ProjectLoader()
        let writer = ProjectWriter()

        let sourcePath = Bundle.module.url(
            forResource: "sample-app.moqproj",
            withExtension: nil,
            subdirectory: "Fixtures"
        )!.path
        let project = try loader.load(from: sourcePath)

        let tempDir = NSTemporaryDirectory()
        let path1 = (tempDir as NSString).appendingPathComponent("det-1-\(UUID().uuidString).moqproj")
        let path2 = (tempDir as NSString).appendingPathComponent("det-2-\(UUID().uuidString).moqproj")
        defer {
            try? FileManager.default.removeItem(atPath: path1)
            try? FileManager.default.removeItem(atPath: path2)
        }

        try writer.write(project, to: path1)
        try writer.write(project, to: path2)

        // Compare project.yml
        let yml1 = try String(contentsOfFile: (path1 as NSString).appendingPathComponent("project.yml"), encoding: .utf8)
        let yml2 = try String(contentsOfFile: (path2 as NSString).appendingPathComponent("project.yml"), encoding: .utf8)
        #expect(yml1 == yml2)

        // Compare endpoint files
        let endDir1 = (path1 as NSString).appendingPathComponent("endpoints")
        let endDir2 = (path2 as NSString).appendingPathComponent("endpoints")
        let files1 = try FileManager.default.contentsOfDirectory(atPath: endDir1).sorted()
        let files2 = try FileManager.default.contentsOfDirectory(atPath: endDir2).sorted()
        #expect(files1 == files2)

        for file in files1 {
            let content1 = try String(contentsOfFile: (endDir1 as NSString).appendingPathComponent(file), encoding: .utf8)
            let content2 = try String(contentsOfFile: (endDir2 as NSString).appendingPathComponent(file), encoding: .utf8)
            #expect(content1 == content2)
        }
    }

    @Test("Writes a fallback alias when an endpoint omits one")
    func writesFallbackAlias() throws {
        let writer = ProjectWriter()
        let project = MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: "Alias Test",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            endpoints: [
                EndpointDocument(
                    id: "delete-pets",
                    alias: nil,
                    method: "DELETE",
                    path: "/pets/{petId}",
                    variants: [ProjectVariant(name: "default", status: 204)]
                )
            ],
            projectPath: "/tmp/alias-test.moqproj"
        )

        let tempDir = NSTemporaryDirectory()
        let outputPath = (tempDir as NSString).appendingPathComponent("alias-save-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let endpointPath = (outputPath as NSString).appendingPathComponent("endpoints/delete-pets.yml")
        let yaml = try String(contentsOfFile: endpointPath, encoding: .utf8)
        #expect(yaml.contains(#"alias: "Delete Pets By Pet Id""#))

        let reloaded = try ProjectLoader().load(from: outputPath)
        #expect(reloaded.endpoints.count == 1)
        #expect(reloaded.endpoints.first?.alias == "Delete Pets By Pet Id")
    }

    @Test("Preserves Studio reference names and match types when writing")
    func preservesStudioMetadata() throws {
        let writer = ProjectWriter()
        let loader = ProjectLoader()
        let project = MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: "Metadata Test",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            endpoints: [
                EndpointDocument(
                    id: "get-users",
                    alias: "Get Users",
                    description: "Used to verify metadata persistence",
                    referenceName: "usersApi",
                    method: "GET",
                    path: "/users",
                    requestRules: RequestRules(
                        headers: [
                            RuleMatcher(name: "X-Request-ID", match: "^req-.*", required: true, matchType: .matchesRegex)
                        ]
                    ),
                    variants: [
                        ProjectVariant(
                            name: "Success",
                            referenceName: "successResponse",
                            isDefault: true,
                            status: 200,
                            body: .object(["ok": .bool(true)])
                        )
                    ]
                )
            ],
            projectPath: "/tmp/metadata-test.moqproj"
        )

        let tempDir = NSTemporaryDirectory()
        let outputPath = (tempDir as NSString).appendingPathComponent("metadata-save-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let endpointPath = (outputPath as NSString).appendingPathComponent("endpoints/get-users.yml")
        let yaml = try String(contentsOfFile: endpointPath, encoding: .utf8)
        #expect(yaml.contains(#"description: "Used to verify metadata persistence""#))
        #expect(yaml.contains(#"reference_name: "usersApi""#))
        #expect(yaml.contains(#"match_type: matches_regex"#))
        #expect(yaml.contains(#"reference_name: "successResponse""#))

        let reloaded = try loader.load(from: outputPath)
        let endpoint = try #require(reloaded.endpoints.first)
        let variant = try #require(endpoint.variants.first)
        #expect(endpoint.referenceName == "usersApi")
        #expect(endpoint.description == "Used to verify metadata persistence")
        #expect(endpoint.requestRules?.headers?.first?.matchType == .matchesRegex)
        #expect(variant.referenceName == "successResponse")
    }

    @Test("Preserves variant request_match when writing")
    func preservesVariantRequestMatch() throws {
        let writer = ProjectWriter()
        let loader = ProjectLoader()
        let project = MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: "Variant Match Test",
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                )
            ),
            endpoints: [
                EndpointDocument(
                    id: "get-users",
                    method: "GET",
                    path: "/users",
                    variants: [
                        ProjectVariant(
                            name: "matched",
                            status: 200,
                            requestMatch: RequestMatch(
                                query: ["type": "active"],
                                headers: ["X-Role": "admin"],
                                bodyContains: "currentUser"
                            ),
                            body: .object(["ok": .bool(true)])
                        )
                    ]
                )
            ],
            projectPath: "/tmp/variant-match-test.moqproj"
        )

        let tempDir = NSTemporaryDirectory()
        let outputPath = (tempDir as NSString).appendingPathComponent("variant-match-save-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let endpointPath = (outputPath as NSString).appendingPathComponent("endpoints/get-users.yml")
        let yaml = try String(contentsOfFile: endpointPath, encoding: .utf8)
        #expect(yaml.contains("request_match:"))
        #expect(yaml.contains("body_contains: \"currentUser\""))

        let reloaded = try loader.load(from: outputPath)
        let variant = try #require(reloaded.endpoints.first?.variants.first)
        #expect(variant.requestMatch?.query == ["type": "active"])
        #expect(variant.requestMatch?.headers == ["X-Role": "admin"])
        #expect(variant.requestMatch?.bodyContains == "currentUser")
    }
}
