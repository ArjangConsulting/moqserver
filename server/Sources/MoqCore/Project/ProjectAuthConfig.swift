/// Auth configuration in the .moqproj format.
public struct ProjectAuthConfig: Codable, Sendable, Equatable {
    /// The authentication type.
    public let type: AuthType
    /// Whether the server should verify auth presence.
    public let verify: Bool
    /// Custom header name (required for api-key and header types).
    public let headerName: String?

    public init(type: AuthType, verify: Bool, headerName: String? = nil) {
        self.type = type
        self.verify = verify
        self.headerName = headerName
    }

    public enum AuthType: String, Codable, Sendable, Equatable {
        case none
        case bearer
        case basic
        case apiKey = "api-key"
        case header
    }

    enum CodingKeys: String, CodingKey {
        case type, verify
        case headerName = "header_name"
    }
}
