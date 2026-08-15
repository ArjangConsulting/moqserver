/// A loaded .moqproj project — the aggregate root for the project format.
///
/// `Codable` so a whole in-memory project can travel as one JSON payload — the shape a stateless
/// `validate` call over a service boundary needs, since the caller (e.g. Studio) is validating an
/// edited, unsaved project rather than one that can be re-read from disk.
public struct MoqProject: Codable, Sendable, Equatable {
    /// The project manifest from project.yml.
    public let manifest: ProjectManifest
    /// All endpoint documents from endpoints/*.yml.
    public let endpoints: [EndpointDocument]
    /// The absolute path to the .moqproj directory on disk. Still meaningful for an unsaved
    /// project: fixture existence and path-traversal checks resolve against it.
    public let projectPath: String

    enum CodingKeys: String, CodingKey {
        case manifest, endpoints
        case projectPath = "project_path"
    }

    public init(manifest: ProjectManifest, endpoints: [EndpointDocument], projectPath: String) {
        self.manifest = manifest
        self.endpoints = endpoints
        self.projectPath = projectPath
    }
}
