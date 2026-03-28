import Foundation
import Testing
import XCTVapor
@testable import MoqCompanionAI

@Suite("PromptBuilder Tests")
struct PromptBuilderTests {
    private let handler = CompanionHandler(registry: ProviderRegistry())

    // MARK: - buildAnalyzePrompt

    @Test("buildAnalyzePrompt with minimal request")
    func analyzePromptMinimal() {
        let request = CompanionRequest(
            providerId: "test",
            projectContext: nil,
            selection: nil,
            intent: nil,
            options: nil
        )
        let prompt = handler.buildAnalyzePrompt(request)
        #expect(prompt.contains("API specification analyzer"))
        #expect(prompt.contains("JSON array"))
    }

    @Test("buildAnalyzePrompt with full project context")
    func analyzePromptFullContext() {
        let request = CompanionRequest(
            providerId: "test",
            projectContext: ProjectContext(
                title: "Pet API",
                version: "2.0",
                endpoints: [
                    EndpointSummary(method: "GET", path: "/pets", variantCount: 3, hasAuth: true, tags: nil),
                    EndpointSummary(method: "POST", path: "/pets", variantCount: 1, hasAuth: false, tags: nil),
                ],
                specExcerpt: "openapi: 3.0.0"
            ),
            selection: nil,
            intent: IntentContext(description: "Find naming issues", type: nil),
            options: nil
        )
        let prompt = handler.buildAnalyzePrompt(request)
        #expect(prompt.contains("Pet API"))
        #expect(prompt.contains("2.0"))
        #expect(prompt.contains("GET /pets"))
        #expect(prompt.contains("3 variants"))
        #expect(prompt.contains("[auth required]"))
        #expect(prompt.contains("POST /pets"))
        #expect(prompt.contains("openapi: 3.0.0"))
        #expect(prompt.contains("Find naming issues"))
    }

    // MARK: - buildGenerateVariantsPrompt

    @Test("buildGenerateVariantsPrompt with selection and intent")
    func generateVariantsPromptWithSelection() {
        let request = CompanionRequest(
            providerId: "test",
            projectContext: ProjectContext(
                title: "My API",
                version: nil,
                endpoints: [EndpointSummary(method: "GET", path: "/users", variantCount: nil, hasAuth: nil, tags: nil)],
                specExcerpt: nil
            ),
            selection: SelectionContext(endpointKeys: ["GET /users", "POST /users"], variantNames: nil),
            intent: IntentContext(description: "Generate error cases", type: "error-cases"),
            options: RequestOptions(model: nil, maxVariants: 5, temperature: nil)
        )
        let prompt = handler.buildGenerateVariantsPrompt(request)
        #expect(prompt.contains("mock API response generator"))
        #expect(prompt.contains("My API"))
        #expect(prompt.contains("GET /users"))
        #expect(prompt.contains("POST /users"))
        #expect(prompt.contains("Generate error cases"))
        #expect(prompt.contains("error-cases"))
        #expect(prompt.contains("up to 5 variants"))
    }

    @Test("buildGenerateVariantsPrompt defaults to 3 max variants")
    func generateVariantsPromptDefaultMaxVariants() {
        let request = CompanionRequest(
            providerId: "test",
            projectContext: nil,
            selection: nil,
            intent: nil,
            options: nil
        )
        let prompt = handler.buildGenerateVariantsPrompt(request)
        #expect(prompt.contains("up to 3 variants"))
    }

    // MARK: - buildRefineProjectPrompt

    @Test("buildRefineProjectPrompt with full context including tags")
    func refineProjectPromptWithTags() {
        let request = CompanionRequest(
            providerId: "test",
            projectContext: ProjectContext(
                title: "Shop API",
                version: nil,
                endpoints: [
                    EndpointSummary(method: "GET", path: "/products", variantCount: 2, hasAuth: nil, tags: ["products", "catalog"]),
                ],
                specExcerpt: nil
            ),
            selection: nil,
            intent: IntentContext(description: "Improve structure", type: nil),
            options: nil
        )
        let prompt = handler.buildRefineProjectPrompt(request)
        #expect(prompt.contains("project structure advisor"))
        #expect(prompt.contains("Shop API"))
        #expect(prompt.contains("1 total"))
        #expect(prompt.contains("GET /products"))
        #expect(prompt.contains("2 variants"))
        #expect(prompt.contains("products, catalog"))
        #expect(prompt.contains("Improve structure"))
    }

    // MARK: - parseAnalyzeResponse

    @Test("parseAnalyzeResponse with valid JSON")
    func parseAnalyzeResponseValid() {
        let json = #"[{"severity":"warning","category":"naming","message":"Bad naming","endpointKey":"GET /users","suggestion":"Use nouns"}]"#
        let result = handler.parseAnalyzeResponse(json)
        #expect(result.findings.count == 1)
        #expect(result.findings[0].severity == .warning)
        #expect(result.findings[0].category == "naming")
        #expect(result.findings[0].message == "Bad naming")
        #expect(result.findings[0].endpointKey == "GET /users")
        #expect(result.findings[0].suggestion == "Use nouns")
    }

    @Test("parseAnalyzeResponse with markdown fences")
    func parseAnalyzeResponseWithFences() {
        let json = """
        ```json
        [{"severity":"info","category":"general","message":"Looks good"}]
        ```
        """
        let result = handler.parseAnalyzeResponse(json)
        #expect(result.findings.count == 1)
        #expect(result.findings[0].severity == .info)
    }

    @Test("parseAnalyzeResponse with bare fences (no language)")
    func parseAnalyzeResponseBareFences() {
        let json = """
        ```
        [{"severity":"error","category":"schema","message":"Missing type"}]
        ```
        """
        let result = handler.parseAnalyzeResponse(json)
        #expect(result.findings.count == 1)
        #expect(result.findings[0].severity == .error)
    }

    @Test("parseAnalyzeResponse with invalid JSON falls back")
    func parseAnalyzeResponseInvalid() {
        let text = "This is not JSON but has some findings"
        let result = handler.parseAnalyzeResponse(text)
        #expect(result.findings.count == 1)
        #expect(result.findings[0].severity == .info)
        #expect(result.findings[0].category == "general")
        #expect(result.findings[0].message == text)
    }

    // MARK: - parseGenerateVariantsResponse

    @Test("parseGenerateVariantsResponse with valid JSON")
    func parseGenerateVariantsValid() {
        let json = #"[{"endpointKey":"GET /users","name":"empty","statusCode":200,"contentType":"application/json","body":"[]","description":"Empty list"}]"#
        let result = handler.parseGenerateVariantsResponse(json)
        #expect(result.variants.count == 1)
        #expect(result.variants[0].name == "empty")
        #expect(result.variants[0].statusCode == 200)
    }

    @Test("parseGenerateVariantsResponse with invalid JSON returns empty")
    func parseGenerateVariantsInvalid() {
        let result = handler.parseGenerateVariantsResponse("not json")
        #expect(result.variants.isEmpty)
    }

    @Test("parseGenerateVariantsResponse with markdown fences")
    func parseGenerateVariantsWithFences() {
        let json = """
        ```json
        [{"endpointKey":"POST /users","name":"created","statusCode":201,"contentType":"application/json","body":"{}"}]
        ```
        """
        let result = handler.parseGenerateVariantsResponse(json)
        #expect(result.variants.count == 1)
    }

    // MARK: - parseRefineProjectResponse

    @Test("parseRefineProjectResponse with valid JSON")
    func parseRefineProjectValid() {
        let json = #"[{"category":"naming","title":"Fix naming","description":"Use plural","affectedEndpoints":["GET /user"]}]"#
        let result = handler.parseRefineProjectResponse(json)
        #expect(result.suggestions.count == 1)
        #expect(result.suggestions[0].title == "Fix naming")
        #expect(result.suggestions[0].affectedEndpoints?.first == "GET /user")
    }

    @Test("parseRefineProjectResponse with invalid JSON returns empty")
    func parseRefineProjectInvalid() {
        let result = handler.parseRefineProjectResponse("garbage data")
        #expect(result.suggestions.isEmpty)
    }
}
