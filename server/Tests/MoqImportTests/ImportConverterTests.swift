import Foundation
import Testing

@testable import MoqCore
@testable import MoqImport

struct ImportConverterTests {
    func parsedEndpoint(
        method: String = "GET",
        path: String = "/users",
        responses: [ParsedResponse] = [ParsedResponse(name: "default", statusCode: 200)],
        tags: [String] = [],
        authType: ProjectAuthConfig.AuthType = .none,
        requiredHeaders: [String] = [],
        cookies: [RuleMatcher] = []
    ) -> ParsedEndpoint {
        ParsedEndpoint(
            method: method, path: path, tags: tags, responses: responses, authType: authType,
            requiredHeaders: requiredHeaders, cookies: cookies)
    }

    // MARK: - convert

    @Test("convert derives a deterministic endpoint id from method and path")
    func convertDerivesEndpointID() {
        let spec = ParsedSpec(
            title: "API", version: "1.0", endpoints: [parsedEndpoint(method: "GET", path: "/users/{id}")])
        let project = ImportConverter.convert(spec, selection: .all, name: "Test", path: "/tmp/test.moqproj")
        #expect(project.endpoints.first?.id == "get-users-param")
    }

    @Test("convert assigns a default variant to the first 2xx response")
    func convertAssignsDefaultVariant() {
        let responses = [
            ParsedResponse(name: "error", statusCode: 404),
            ParsedResponse(name: "ok", statusCode: 200),
        ]
        let spec = ParsedSpec(title: "API", version: "1.0", endpoints: [parsedEndpoint(responses: responses)])
        let project = ImportConverter.convert(spec, selection: .all, name: "Test", path: "/tmp/test.moqproj")
        let variants = project.endpoints[0].variants
        #expect(variants.first { $0.status == 200 }?.isDefault == true)
        #expect(variants.first { $0.status == 404 }?.isDefault != true)
    }

    @Test("convert filters endpoints by ImportSelection paths")
    func convertFiltersBySelection() {
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [parsedEndpoint(path: "/users"), parsedEndpoint(path: "/orders")])
        let project = ImportConverter.convert(
            spec, selection: ImportSelection(acceptedPaths: ["/users"]), name: "Test", path: "/tmp/test.moqproj")
        #expect(project.endpoints.map(\.path) == ["/users"])
    }

    @Test("convert assigns unique reference names across endpoints with colliding aliases")
    func convertAssignsUniqueReferenceNames() {
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [
                parsedEndpoint(
                    method: "GET", path: "/a", responses: [ParsedResponse(name: "default", statusCode: 200)]),
                parsedEndpoint(
                    method: "GET", path: "/b", responses: [ParsedResponse(name: "default", statusCode: 200)]),
            ])
        // Force identical aliases by giving both the same referenceName hint indirectly via alias.
        let forced = spec.endpoints.map { endpoint in
            ParsedEndpoint(
                method: endpoint.method, path: endpoint.path, alias: "Same Alias", responses: endpoint.responses)
        }
        let project = ImportConverter.convert(
            ParsedSpec(title: "API", version: "1.0", endpoints: forced),
            selection: .all, name: "Test", path: "/tmp/test.moqproj")
        let referenceNames = project.endpoints.map(\.referenceName)
        #expect(Set(referenceNames).count == referenceNames.count)
    }

    @Test("convert sets auth from a non-none authType")
    func convertSetsAuth() {
        let spec = ParsedSpec(title: "API", version: "1.0", endpoints: [parsedEndpoint(authType: .bearer)])
        let project = ImportConverter.convert(spec, selection: .all, name: "Test", path: "/tmp/test.moqproj")
        #expect(project.endpoints[0].auth?.type == .bearer)
        #expect(project.endpoints[0].auth?.verify == true)
    }

    // MARK: - merge

    @Test("merge appends a genuinely new endpoint")
    func mergeAppendsNewEndpoint() {
        let existing = MoqProject(
            manifest: sampleManifest(), endpoints: [sampleEndpoint(id: "get-a", path: "/a")], projectPath: "/tmp/x")
        let spec = ParsedSpec(title: "API", version: "1.0", endpoints: [parsedEndpoint(path: "/b")])

        let merged = ImportConverter.merge(spec, selection: .all, policy: ImportMergePolicy(), into: existing)
        #expect(merged.endpoints.map(\.id).sorted() == ["get-a", "get-b"])
    }

    @Test("merge never removes an existing endpoint or variant the spec omits")
    func mergeNeverRemoves() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                sampleEndpoint(
                    id: "get-users", path: "/users",
                    variants: [
                        ProjectVariant(name: "ok", status: 200), ProjectVariant(name: "not-found", status: 404),
                    ])
            ],
            projectPath: "/tmp/x")
        // Spec only knows about the 200 response now — merge must not drop the 404 variant.
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [parsedEndpoint(responses: [ParsedResponse(name: "ok", statusCode: 200)])])

        let merged = ImportConverter.merge(spec, selection: .all, policy: ImportMergePolicy(), into: existing)
        let variantStatuses = merged.endpoints[0].variants.map(\.status).sorted()
        #expect(variantStatuses == [200, 404])
    }

    @Test("merge adds a new status-code variant to an existing endpoint")
    func mergeAddsNewVariant() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                sampleEndpoint(id: "get-users", path: "/users", variants: [ProjectVariant(name: "ok", status: 200)])
            ],
            projectPath: "/tmp/x")
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [
                parsedEndpoint(
                    responses: [
                        ParsedResponse(name: "ok", statusCode: 200),
                        ParsedResponse(name: "error", statusCode: 500),
                    ])
            ])

        let merged = ImportConverter.merge(spec, selection: .all, policy: ImportMergePolicy(), into: existing)
        #expect(merged.endpoints[0].variants.map(\.status).sorted() == [200, 500])
    }

    @Test("merge preserves an existing variant's body when replaceExistingBodies is false")
    func mergePreservesBodyByDefault() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                sampleEndpoint(
                    id: "get-users", path: "/users",
                    variants: [ProjectVariant(name: "ok", status: 200, body: .string("original"))])
            ],
            projectPath: "/tmp/x")
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [
                parsedEndpoint(responses: [ParsedResponse(name: "ok", statusCode: 200, body: "{\"new\":true}")])
            ])

        let merged = ImportConverter.merge(spec, selection: .all, policy: ImportMergePolicy(), into: existing)
        #expect(merged.endpoints[0].variants[0].body == .string("original"))
    }

    @Test("merge replaces a matching variant's body when replaceExistingBodies is true")
    func mergeReplacesBodyWhenRequested() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                sampleEndpoint(
                    id: "get-users", path: "/users",
                    variants: [ProjectVariant(name: "ok", status: 200, body: .string("original"))])
            ],
            projectPath: "/tmp/x")
        let spec = ParsedSpec(
            title: "API", version: "1.0",
            endpoints: [parsedEndpoint(responses: [ParsedResponse(name: "ok", statusCode: 200, body: "updated")])])

        let merged = ImportConverter.merge(
            spec, selection: .all, policy: ImportMergePolicy(replaceExistingBodies: true), into: existing)
        #expect(merged.endpoints[0].variants[0].body == .string("updated"))
    }

    @Test("merge preserves user-owned fields: alias, description, reference_name, delay")
    func mergePreservesUserOwnedFields() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [
                EndpointDocument(
                    id: "get-users", alias: "My Custom Alias", description: "hand-written",
                    referenceName: "myCustomRef", method: "GET", path: "/users",
                    variants: [ProjectVariant(name: "ok", status: 200, delayMs: 250)])
            ],
            projectPath: "/tmp/x")
        let spec = ParsedSpec(title: "API", version: "1.0", endpoints: [parsedEndpoint(tags: ["new-tag"])])

        let merged = ImportConverter.merge(spec, selection: .all, policy: ImportMergePolicy(), into: existing)
        #expect(merged.endpoints[0].alias == "My Custom Alias")
        #expect(merged.endpoints[0].description == "hand-written")
        #expect(merged.endpoints[0].referenceName == "myCustomRef")
        #expect(merged.endpoints[0].variants[0].delayMs == 250)
    }

    @Test("merge updates spec-owned tags only when updateDetails is true")
    func mergeUpdatesTagsOnlyWhenRequested() {
        let existing = MoqProject(
            manifest: sampleManifest(),
            endpoints: [sampleEndpoint(id: "get-users", path: "/users", tags: ["old"])],
            projectPath: "/tmp/x")
        let spec = ParsedSpec(title: "API", version: "1.0", endpoints: [parsedEndpoint(tags: ["new"])])

        let notUpdated = ImportConverter.merge(
            spec, selection: .all, policy: ImportMergePolicy(updateDetails: false), into: existing)
        #expect(notUpdated.endpoints[0].tags == ["old"])

        let updated = ImportConverter.merge(
            spec, selection: .all, policy: ImportMergePolicy(updateDetails: true), into: existing)
        #expect(updated.endpoints[0].tags == ["new"])
    }

    // MARK: - diff

    @Test("diff reports new and removed status codes")
    func diffReportsStatusCodeChanges() {
        let existing = sampleEndpoint(
            id: "get-users", path: "/users",
            variants: [ProjectVariant(name: "ok", status: 200), ProjectVariant(name: "gone", status: 410)])
        let parsed = parsedEndpoint(
            responses: [ParsedResponse(name: "ok", statusCode: 200), ParsedResponse(name: "err", statusCode: 500)])

        let diff = ImportConverter.diff(parsed, existing: existing)
        #expect(diff.newStatusCodes == [500])
        #expect(diff.removedStatusCodes == [410])
        #expect(diff.hasChanges)
    }

    @Test("diff reports no changes for an identical endpoint")
    func diffReportsNoChanges() {
        let existing = sampleEndpoint(
            id: "get-users", path: "/users", variants: [ProjectVariant(name: "ok", status: 200)])
        let parsed = parsedEndpoint(responses: [ParsedResponse(name: "ok", statusCode: 200)])

        let diff = ImportConverter.diff(parsed, existing: existing)
        #expect(!diff.hasChanges)
    }

    // MARK: - endpointID

    @Test(
        "endpointID normalizes path parameters and strips unsafe characters",
        arguments: [
            ("GET", "/users", "get-users"),
            ("GET", "/users/{id}", "get-users-param"),
            ("POST", "/", "post-root"),
            ("DELETE", "/a/{id}/b/{other}", "delete-a-param-b-param"),
        ]
    )
    func endpointIDNormalization(method: String, path: String, expected: String) {
        #expect(ImportConverter.endpointID(method: method, path: path) == expected)
    }

    // MARK: - helpers

    private func sampleManifest() -> ProjectManifest {
        ProjectManifest(
            version: "1", name: "Test",
            defaults: ProjectDefaults(
                delayMs: 0, auth: ProjectAuthConfig(type: .none, verify: false), network: NetworkBehavior())
        )
    }

    private func sampleEndpoint(
        id: String, path: String, tags: [String]? = nil,
        variants: [ProjectVariant] = [ProjectVariant(name: "default", status: 200)]
    ) -> EndpointDocument {
        EndpointDocument(id: id, method: "GET", path: path, tags: tags, variants: variants)
    }
}
