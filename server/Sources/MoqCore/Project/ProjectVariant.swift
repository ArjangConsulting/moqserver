/// A response variant in the .moqproj format.
public struct ProjectVariant: Codable, Sendable, Equatable {
    /// Variant name used for selection.
    public let name: String
    /// Whether this is the default variant.
    public let isDefault: Bool?
    /// HTTP status code.
    public let status: Int
    /// Response headers.
    public let headers: [String: String]?
    /// Inline response body (arbitrary YAML/JSON value).
    public let body: AnyCodableValue?
    /// Path to external fixture file (relative to project root, must start with "fixtures/").
    public let bodyFile: String?
    /// Additional delay in milliseconds.
    public let delayMs: Int?

    public init(
        name: String,
        isDefault: Bool? = nil,
        status: Int,
        headers: [String: String]? = nil,
        body: AnyCodableValue? = nil,
        bodyFile: String? = nil,
        delayMs: Int? = nil
    ) {
        self.name = name
        self.isDefault = isDefault
        self.status = status
        self.headers = headers
        self.body = body
        self.bodyFile = bodyFile
        self.delayMs = delayMs
    }

    enum CodingKeys: String, CodingKey {
        case name
        case isDefault = "default"
        case status, headers, body
        case bodyFile = "body_file"
        case delayMs = "delay_ms"
    }
}
