/// The project manifest, representing the contents of project.yml.
public struct ProjectManifest: Codable, Sendable, Equatable {
    /// Format version (must be "1" for v1).
    public let version: String
    /// Human-readable project name.
    public let name: String
    /// Optional project description.
    public let description: String?
    /// Default settings applied to all endpoints.
    public let defaults: ProjectDefaults
    /// Global request validation rules.
    public let globalRules: GlobalRules?

    public init(
        version: String = "1",
        name: String,
        description: String? = nil,
        defaults: ProjectDefaults,
        globalRules: GlobalRules? = nil
    ) {
        self.version = version
        self.name = name
        self.description = description
        self.defaults = defaults
        self.globalRules = globalRules
    }

    enum CodingKeys: String, CodingKey {
        case version, name, description, defaults
        case globalRules = "global_rules"
    }
}
