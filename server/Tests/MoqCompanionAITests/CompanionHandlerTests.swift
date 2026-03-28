import Testing
import VaporTesting
import XCTVapor
@testable import MoqCompanionAI

@Suite("CompanionHandler Tests")
struct CompanionHandlerTests {
    @Test("GET /health returns ok")
    func healthEndpoint() async throws {
        let app = try await buildTestApp()
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "health") { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(HealthResponse.self)
            #expect(body?.status == "ok")
            #expect(body?.version == "1.0.0")
        }
    }

    @Test("GET /ai/providers returns registered providers")
    func providersEndpoint() async throws {
        let app = try await buildTestApp()
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.GET, "ai/providers") { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(ProvidersResponse.self)
            #expect(body?.providers.count == 1)
            #expect(body?.providers.first?.id == "stub")
            #expect(body?.providers.first?.available == true)
        }
    }

    @Test("POST /ai/validate-config with known provider")
    func validateConfigKnown() async throws {
        let app = try await buildTestApp()
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/validate-config", beforeRequest: { req async throws in
            try req.content.encode(ValidateConfigRequest(providerId: "stub"))
        }) { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(ValidateConfigResponse.self)
            #expect(body?.valid == true)
        }
    }

    @Test("POST /ai/validate-config with unknown provider returns error")
    func validateConfigUnknown() async throws {
        let app = try await buildTestApp()
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/validate-config", beforeRequest: { req async throws in
            try req.content.encode(ValidateConfigRequest(providerId: "nonexistent"))
        }) { res async in
            #expect(res.status == .badRequest)
        }
    }

    @Test("POST /ai/analyze-spec returns structured findings")
    func analyzeSpec() async throws {
        let stubbedResponse = #"[{"severity":"warning","category":"naming","message":"Inconsistent naming","endpointKey":"GET /users","suggestion":"Use plural nouns"}]"#
        let app = try await buildTestApp(completionText: stubbedResponse)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/analyze-spec", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: ProjectContext(title: "Test API", version: "1.0", endpoints: nil, specExcerpt: nil),
                selection: nil,
                intent: nil,
                options: nil
            ))
        }) { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(CompanionResponse<AnalyzeSpecResult>.self)
            #expect(body?.result.findings.count == 1)
            #expect(body?.result.findings.first?.severity == .warning)
            #expect(body?.provider.id == "stub")
        }
    }

    @Test("POST /ai/generate-variants returns structured variants")
    func generateVariants() async throws {
        let stubbedResponse = #"[{"endpointKey":"GET /users","name":"empty-list","statusCode":200,"contentType":"application/json","body":"[]","description":"No users"}]"#
        let app = try await buildTestApp(completionText: stubbedResponse)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/generate-variants", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: nil,
                selection: SelectionContext(endpointKeys: ["GET /users"], variantNames: nil),
                intent: IntentContext(description: "Generate error cases", type: "error-cases"),
                options: nil
            ))
        }) { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(CompanionResponse<GenerateVariantsResult>.self)
            #expect(body?.result.variants.count == 1)
            #expect(body?.result.variants.first?.name == "empty-list")
        }
    }

    @Test("POST /ai/refine-project returns structured suggestions")
    func refineProject() async throws {
        let stubbedResponse = #"[{"category":"naming","title":"Inconsistent naming","description":"Use plural nouns for collections","affectedEndpoints":["GET /user"]}]"#
        let app = try await buildTestApp(completionText: stubbedResponse)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/refine-project", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: nil,
                selection: nil,
                intent: nil,
                options: nil
            ))
        }) { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(CompanionResponse<RefineProjectResult>.self)
            #expect(body?.result.suggestions.count == 1)
            #expect(body?.result.suggestions.first?.category == "naming")
        }
    }

    @Test("AI endpoints redact secrets for hosted providers")
    func redactionForHostedProviders() async throws {
        let stubbedResponse = "[]"
        let app = try await buildTestApp(completionText: stubbedResponse, providerKind: .hosted)
        defer { Task { try? await app.asyncShutdown() } }

        try await app.testing().test(.POST, "ai/analyze-spec", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: ProjectContext(
                    title: "Test",
                    version: "1.0",
                    endpoints: nil,
                    specExcerpt: "Authorization: Bearer my-secret-token-123"
                ),
                selection: nil,
                intent: nil,
                options: nil
            ))
        }) { res async in
            #expect(res.status == .ok)
            let body = try? res.content.decode(CompanionResponse<AnalyzeSpecResult>.self)
            // Should have a redaction warning
            #expect(body?.warnings?.first?.contains("Redacted") == true)
        }
    }

    @Test("Hosted provider requests are rejected when redaction policy threshold is exceeded")
    func redactionPolicyRejectsHostedPayloads() async throws {
        let app = try await buildTestApp(completionText: "[]", providerKind: .hosted)
        defer { Task { try? await app.asyncShutdown() } }

        let sensitiveExcerpt = (0..<60)
            .map { "Authorization: Bearer secret-token-\($0)" }
            .joined(separator: "\n")

        try await app.testing().test(.POST, "ai/analyze-spec", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: ProjectContext(
                    title: "Test",
                    version: "1.0",
                    endpoints: nil,
                    specExcerpt: sensitiveExcerpt
                ),
                selection: nil,
                intent: nil,
                options: nil
            ))
        }) { res async in
            #expect(res.status == .badRequest)
            let body = try? res.content.decode(CompanionErrorResponse.self)
            #expect(body?.error.code == .requestRejectedRedactionPolicy)
        }
    }

    @Test("Oversized requests are rejected before provider execution")
    func oversizedRequestRejected() async throws {
        let app = try await buildTestApp(completionText: "[]", providerKind: .local)
        defer { Task { try? await app.asyncShutdown() } }

        let largeExcerpt = String(repeating: "a", count: 120_000)

        try await app.testing().test(.POST, "ai/analyze-spec", beforeRequest: { req async throws in
            try req.content.encode(CompanionRequest(
                providerId: "stub",
                projectContext: ProjectContext(
                    title: "Large",
                    version: "1.0",
                    endpoints: nil,
                    specExcerpt: largeExcerpt
                ),
                selection: nil,
                intent: nil,
                options: nil
            ))
        }) { res async in
            #expect(res.status == .payloadTooLarge)
            let body = try? res.content.decode(CompanionErrorResponse.self)
            #expect(body?.error.code == .requestTooLarge)
        }
    }

    // MARK: - Test Helpers

    private func buildTestApp(completionText: String = "[]", providerKind: ProviderKind = .local) async throws -> Application {
        let registry = ProviderRegistry()
        let provider = TestableProvider(
            id: "stub",
            displayName: "Stub",
            kind: providerKind,
            completionText: completionText
        )
        await registry.register(provider)

        return try await buildCompanionApp(
            registry: registry,
            hostname: "127.0.0.1",
            port: 0 // random port for tests
        )
    }
}

// MARK: - Test Doubles

private struct TestableProvider: AIProvider {
    let id: String
    let displayName: String
    let kind: ProviderKind
    let completionText: String
    let capabilities: [AICapability] = [.analyzeSpec, .generateVariants, .refineProject]

    func checkAvailability() async -> Bool { true }
    func validateConfig() async -> ValidateConfigResponse { ValidateConfigResponse(valid: true) }

    func complete(prompt: String, model: String?, temperature: Double?) async throws -> AICompletionResult {
        AICompletionResult(text: completionText, model: model ?? "stub-model")
    }
}
