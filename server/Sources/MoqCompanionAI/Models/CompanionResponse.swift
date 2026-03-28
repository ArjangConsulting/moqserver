import Vapor

/// Common response envelope for all AI action endpoints.
public struct CompanionResponse<T: Content & Sendable>: Content, Sendable {
    public let requestId: String
    public let provider: ProviderUsage
    public let result: T
    public let warnings: [String]?
    public let usage: UsageInfo?

    public init(requestId: String, provider: ProviderUsage, result: T, warnings: [String]? = nil, usage: UsageInfo? = nil) {
        self.requestId = requestId
        self.provider = provider
        self.result = result
        self.warnings = warnings
        self.usage = usage
    }
}

public struct ProviderUsage: Content, Sendable {
    public let id: String
    public let model: String

    public init(id: String, model: String) {
        self.id = id
        self.model = model
    }
}

public struct UsageInfo: Content, Sendable {
    public let promptTokens: Int?
    public let completionTokens: Int?
    public let totalTokens: Int?
    public let latencyMs: Int?

    public init(promptTokens: Int? = nil, completionTokens: Int? = nil, totalTokens: Int? = nil, latencyMs: Int? = nil) {
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
        self.totalTokens = totalTokens
        self.latencyMs = latencyMs
    }
}

// MARK: - Health Response

public struct HealthResponse: Content, Sendable {
    public let status: String
    public let version: String

    public init(status: String = "ok", version: String = "1.0.0") {
        self.status = status
        self.version = version
    }
}

// MARK: - Provider Discovery Response

public struct ProvidersResponse: Content, Sendable {
    public let providers: [ProviderInfo]
}

public struct ProviderInfo: Content, Sendable {
    public let id: String
    public let displayName: String
    public let kind: ProviderKind
    public let available: Bool
    public let capabilities: [AICapability]

    public init(id: String, displayName: String, kind: ProviderKind, available: Bool, capabilities: [AICapability]) {
        self.id = id
        self.displayName = displayName
        self.kind = kind
        self.available = available
        self.capabilities = capabilities
    }
}

public enum ProviderKind: String, Content, Sendable {
    case local
    case hosted
}

public enum AICapability: String, Content, Sendable {
    case analyzeSpec = "analyze-spec"
    case generateVariants = "generate-variants"
    case refineProject = "refine-project"
}

// MARK: - Validate Config Response

public struct ValidateConfigResponse: Content, Sendable {
    public let valid: Bool
    public let issues: [String]

    public init(valid: Bool, issues: [String] = []) {
        self.valid = valid
        self.issues = issues
    }
}

// MARK: - Analyze Spec Result

public struct AnalyzeSpecResult: Content, Sendable {
    public let findings: [SpecFinding]
}

public struct SpecFinding: Content, Sendable {
    public let severity: FindingSeverity
    public let category: String
    public let message: String
    public let endpointKey: String?
    public let suggestion: String?
}

public enum FindingSeverity: String, Content, Sendable {
    case info
    case warning
    case error
}

// MARK: - Generate Variants Result

public struct GenerateVariantsResult: Content, Sendable {
    public let variants: [GeneratedVariant]
}

public struct GeneratedVariant: Content, Sendable {
    public let endpointKey: String
    public let name: String
    public let statusCode: Int
    public let contentType: String
    public let body: String
    public let description: String?
}

// MARK: - Refine Project Result

public struct RefineProjectResult: Content, Sendable {
    public let suggestions: [ProjectSuggestion]
}

public struct ProjectSuggestion: Content, Sendable {
    public let category: String
    public let title: String
    public let description: String
    public let affectedEndpoints: [String]?
}
