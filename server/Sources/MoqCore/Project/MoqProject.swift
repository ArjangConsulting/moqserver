/// A loaded .moqproj project — the aggregate root for the project format.
public struct MoqProject: Sendable, Equatable {
    /// The project manifest from project.yml.
    public let manifest: ProjectManifest
    /// All endpoint documents from endpoints/*.yml.
    public let endpoints: [EndpointDocument]
    /// The absolute path to the .moqproj directory on disk.
    public let projectPath: String

    public init(manifest: ProjectManifest, endpoints: [EndpointDocument], projectPath: String) {
        self.manifest = manifest
        self.endpoints = endpoints
        self.projectPath = projectPath
    }
}
