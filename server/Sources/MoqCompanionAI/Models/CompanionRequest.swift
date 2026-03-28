import Vapor

/// Common request envelope for all AI action endpoints.
public struct CompanionRequest: Content, Sendable {
    /// Which provider to use (e.g. "ollama", "openai", "anthropic").
    public let providerId: String

    /// Project context: spec summary, endpoints, existing variants.
    public let projectContext: ProjectContext?

    /// What the user selected (specific endpoints, variant groups).
    public let selection: SelectionContext?

    /// What the user wants to accomplish.
    public let intent: IntentContext?

    /// Provider-specific or action-specific options.
    public let options: RequestOptions?
}

public struct ProjectContext: Content, Sendable {
    /// Project title from the spec or manifest.
    public let title: String?

    /// API version string.
    public let version: String?

    /// Summary of endpoints: method, path, variant count.
    public let endpoints: [EndpointSummary]?

    /// Raw spec excerpt (will be redacted before forwarding to hosted providers).
    public let specExcerpt: String?
}

public struct EndpointSummary: Content, Sendable {
    public let method: String
    public let path: String
    public let variantCount: Int?
    public let hasAuth: Bool?
    public let tags: [String]?
}

public struct SelectionContext: Content, Sendable {
    /// Specific endpoint keys to operate on (e.g. ["GET /users", "POST /users"]).
    public let endpointKeys: [String]?

    /// Variant names to focus on.
    public let variantNames: [String]?
}

public struct IntentContext: Content, Sendable {
    /// Free-form description of what the user wants.
    public let description: String?

    /// Structured intent type: "error-cases", "happy-path", "edge-cases", etc.
    public let type: String?
}

public struct RequestOptions: Content, Sendable {
    /// Model override (e.g. "llama3.1", "gpt-4o").
    public let model: String?

    /// Maximum variants to generate per endpoint.
    public let maxVariants: Int?

    /// Temperature for generation (0.0–1.0).
    public let temperature: Double?
}

/// Minimal request for validate-config (only needs providerId).
public struct ValidateConfigRequest: Content, Sendable {
    public let providerId: String
}
