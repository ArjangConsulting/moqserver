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
    /// Add-ons this bundle enables, keyed by add-on id, each with its own opaque config. The
    /// config shape is owned by the add-on and checked by it at validation time, not by core.
    public let addons: [String: AnyCodableValue]?
    /// Named variant selections tests can activate at runtime (`PUT /_admin/scenario`), keyed by
    /// scenario name. Loaded into the server (and every session) at startup.
    public let scenarios: [String: ProjectScenario]?

    public init(
        version: String = "1",
        name: String,
        description: String? = nil,
        defaults: ProjectDefaults,
        globalRules: GlobalRules? = nil,
        addons: [String: AnyCodableValue]? = nil,
        scenarios: [String: ProjectScenario]? = nil
    ) {
        self.version = version
        self.name = name
        self.description = description
        self.defaults = defaults
        self.globalRules = globalRules
        self.addons = addons
        self.scenarios = scenarios
    }

    enum CodingKeys: String, CodingKey {
        case version, name, description, defaults
        case globalRules = "global_rules"
        case addons
        case scenarios
    }
}

/// A scenario declared in `project.yml`: which variant each listed endpoint serves.
public struct ProjectScenario: Codable, Sendable, Equatable {
    public let description: String?
    /// Endpoint id → variant name (or reference_name).
    public let variants: [String: String]

    public init(description: String? = nil, variants: [String: String]) {
        self.description = description
        self.variants = variants
    }
}
