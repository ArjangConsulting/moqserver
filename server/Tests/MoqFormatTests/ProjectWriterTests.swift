import Foundation
import Testing
@testable import MoqCore
@testable import MoqFormat

@Suite("ProjectWriter - Round-trip")
struct ProjectWriterTests {
    func makeProject(
        name: String = "Writer Test",
        globalRules: GlobalRules? = nil,
        endpoints: [EndpointDocument]
    ) -> MoqProject {
        MoqProject(
            manifest: ProjectManifest(
                version: "1",
                name: name,
                defaults: ProjectDefaults(
                    delayMs: 0,
                    auth: ProjectAuthConfig(type: .none, verify: false),
                    network: NetworkBehavior()
                ),
                globalRules: globalRules
            ),
            endpoints: endpoints,
            projectPath: "/tmp/\(name.replacingOccurrences(of: " ", with: "-").lowercased()).moqproj"
        )
    }

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

    @Test("Writes body shapes deterministically and reloads them")
    func writesBodyShapesAndReloadsThem() throws {
        let writer = ProjectWriter()
        let loader = ProjectLoader()
        let project = makeProject(endpoints: [
            EndpointDocument(
                id: "body-shapes",
                method: "POST",
                path: "/shapes",
                variants: [
                    ProjectVariant(name: "null-body", status: 200, body: .null),
                    ProjectVariant(name: "string-body", status: 201, body: .string("hello")),
                    ProjectVariant(name: "array-body", status: 202, body: .array([.int(1), .string("two"), .bool(true)])),
                    ProjectVariant(name: "empty-object", status: 203, body: .object([:])),
                    ProjectVariant(
                        name: "nested-object",
                        status: 204,
                        body: .object([
                            "items": .array([.int(1), .int(2)]),
                            "nested": .object(["value": .string("ok")]),
                        ])
                    ),
                ]
            ),
        ])

        let outputPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("body-shapes-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let endpointPath = (outputPath as NSString).appendingPathComponent("endpoints/body-shapes.yml")
        let yaml = try String(contentsOfFile: endpointPath, encoding: .utf8)
        #expect(yaml.contains("body: null"))
        #expect(yaml.contains(#"body: "hello""#))
        #expect(yaml.contains(#"body: [1, "two", true]"#))
        #expect(yaml.contains("body: {}"))
        #expect(yaml.contains("nested:"))
        #expect(yaml.contains("items: [1, 2]"))

        let reloaded = try loader.load(from: outputPath)
        let variants = try #require(reloaded.endpoints.first?.variants)
        #expect(variants.first { $0.name == "null-body" }?.body == .null)
        #expect(variants.first { $0.name == "string-body" }?.body == .string("hello"))
        #expect(variants.first { $0.name == "array-body" }?.body == .array([.int(1), .string("two"), .bool(true)]))
        #expect(variants.first { $0.name == "empty-object" }?.body == .object([:]))
    }

    @Test("Writes empty rules selective request matches auth headers and network fields")
    func writesRulesAndNetworkFields() throws {
        let writer = ProjectWriter()
        let loader = ProjectLoader()
        let project = makeProject(
            globalRules: GlobalRules(requiredHeaders: [], verifyCookies: true),
            endpoints: [
                EndpointDocument(
                    id: "graphql-users",
                    method: "POST",
                    path: "/graphql",
                    auth: ProjectAuthConfig(type: .apiKey, verify: true, headerName: "X-API-Key"),
                    requestRules: RequestRules(
                        headers: [RuleMatcher(name: "Accept", match: "application/json", required: true, matchType: .equalTo)],
                        verifyCookies: false,
                        queryParams: [],
                        cookies: []
                    ),
                    operation: EndpointOperation(type: .query, document: "query {\n  currentUser { id }\n}"),
                    network: NetworkBehavior(latencyMs: 5, jitterMs: 1, packetLossPercent: 12.5),
                    variants: [
                        ProjectVariant(name: "default", status: 200, requestMatch: RequestMatch()),
                        ProjectVariant(name: "query-match", status: 201, requestMatch: RequestMatch(query: ["mode": "full"])),
                        ProjectVariant(name: "header-match", status: 202, requestMatch: RequestMatch(headers: ["X-Role": "admin"])),
                        ProjectVariant(name: "body-match", status: 203, requestMatch: RequestMatch(bodyContains: "currentUser")),
                    ]
                ),
            ]
        )

        let outputPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("writer-rules-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let manifestYAML = try String(contentsOfFile: (outputPath as NSString).appendingPathComponent("project.yml"), encoding: .utf8)
        #expect(manifestYAML.contains("required_headers: []"))

        let endpointYAML = try String(contentsOfFile: (outputPath as NSString).appendingPathComponent("endpoints/graphql-users.yml"), encoding: .utf8)
        #expect(endpointYAML.contains("query_params: []"))
        #expect(endpointYAML.contains("cookies: []"))
        #expect(endpointYAML.contains("header_name: X-API-Key"))
        #expect(endpointYAML.contains("packet_loss_percent: 12.5"))
        #expect(endpointYAML.contains("document: |"))
        #expect(endpointYAML.components(separatedBy: "request_match:").count - 1 == 3)

        let reloaded = try loader.load(from: outputPath)
        let endpoint = try #require(reloaded.endpoints.first)
        #expect(endpoint.auth?.headerName == "X-API-Key")
        #expect(endpoint.requestRules?.queryParams?.isEmpty == true)
        #expect(endpoint.requestRules?.cookies?.isEmpty == true)
        #expect(endpoint.operation?.document?.contains("currentUser") == true)
        #expect(endpoint.network?.packetLossPercent == 12.5)
        #expect(endpoint.variants.first { $0.name == "default" }?.requestMatch == nil)
    }

    @Test("Writes quoted strings and non-empty global and query rules")
    func writesQuotedStringsAndNonEmptyRules() throws {
        let writer = ProjectWriter()
        let loader = ProjectLoader()
        let project = makeProject(
            name: "Writer: \"Quotes\" #Test",
            globalRules: GlobalRules(requiredHeaders: [
                RuleMatcher(name: "X-Env", match: "true", required: true),
            ], verifyCookies: false),
            endpoints: [
                EndpointDocument(
                    id: "quoted-fields",
                    method: "GET",
                    path: "/quoted",
                    requestRules: RequestRules(
                        queryParams: [
                            RuleMatcher(name: "search", match: " spaced ", required: false),
                        ]
                    ),
                    variants: [
                        ProjectVariant(name: "special-string", status: 200, body: .string("say \"hi\": #tag")),
                        ProjectVariant(name: "spaced-string", status: 201, body: .string(" spaced ")),
                        ProjectVariant(name: "special-bool-string", status: 202, body: .array([.null, .double(1.5), .string("true") ])),
                    ]
                ),
            ]
        )

        let outputPath = (NSTemporaryDirectory() as NSString).appendingPathComponent("writer-quoted-\(UUID().uuidString).moqproj")
        defer { try? FileManager.default.removeItem(atPath: outputPath) }

        try writer.write(project, to: outputPath)

        let manifestYAML = try String(contentsOfFile: (outputPath as NSString).appendingPathComponent("project.yml"), encoding: .utf8)
        #expect(manifestYAML.contains("required_headers:"))
        #expect(manifestYAML.contains(#"match: "true""#))

        let endpointYAML = try String(contentsOfFile: (outputPath as NSString).appendingPathComponent("endpoints/quoted-fields.yml"), encoding: .utf8)
        #expect(endpointYAML.contains("query_params:"))
        #expect(endpointYAML.contains(#"match: " spaced ""#))
        #expect(endpointYAML.contains(#"body: "say \"hi\": #tag""#))
        #expect(endpointYAML.contains(#"body: " spaced ""#))
        #expect(endpointYAML.contains(#"body: [null, 1.5, "true"]"#))

        let reloaded = try loader.load(from: outputPath)
        let endpoint = try #require(reloaded.endpoints.first)
        #expect(reloaded.manifest.globalRules?.requiredHeaders?.first?.match == "true")
        #expect(endpoint.requestRules?.queryParams?.first?.match == " spaced ")
        #expect(endpoint.variants.first { $0.name == "special-string" }?.body == .string("say \"hi\": #tag"))
        #expect(endpoint.variants.first { $0.name == "spaced-string" }?.body == .string(" spaced "))
        #expect(endpoint.variants.first { $0.name == "special-bool-string" }?.body == .array([.null, .double(1.5), .string("true")]))
    }
}
