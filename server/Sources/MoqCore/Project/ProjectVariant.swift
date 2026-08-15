/// A response variant in the .moqproj format.
public struct ProjectVariant: Codable, Sendable, Equatable {
    /// Variant name used for selection.
    public let name: String
    /// Stable code-friendly identifier used by Studio and automation.
    public let referenceName: String
    /// Optional human-readable description.
    public let description: String?
    /// Whether this is the default variant.
    public let isDefault: Bool?
    /// HTTP status code.
    public let status: Int
    /// Response headers.
    public let headers: [String: String]?
    /// Optional request match criteria used to auto-select this variant.
    public let requestMatch: RequestMatch?
    /// Inline response body (arbitrary YAML/JSON value).
    public let body: AnyCodableValue?
    /// Path to external fixture file (relative to project root, must start with "fixtures/").
    public let bodyFile: String?
    /// Additional delay in milliseconds.
    public let delayMs: Int?

    public init(
        name: String,
        referenceName: String? = nil,
        description: String? = nil,
        isDefault: Bool? = nil,
        status: Int,
        headers: [String: String]? = nil,
        requestMatch: RequestMatch? = nil,
        body: AnyCodableValue? = nil,
        bodyFile: String? = nil,
        delayMs: Int? = nil
    ) {
        let normalizedReferenceName = referenceName?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = name
        self.referenceName =
            normalizedReferenceName.flatMap { $0.isEmpty ? nil : $0 }
            ?? defaultReferenceNameForVariantName(name)
        self.description = description
        self.isDefault = isDefault
        self.status = status
        self.headers = headers
        self.requestMatch = requestMatch
        self.body = body
        self.bodyFile = bodyFile
        self.delayMs = delayMs
    }

    enum CodingKeys: String, CodingKey {
        case name
        case referenceName = "reference_name"
        case description
        case isDefault = "default"
        case status, headers, body, requestMatch = "request_match"
        case bodyFile = "body_file"
        case delayMs = "delay_ms"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let name = try container.decode(String.self, forKey: .name)
        let body: AnyCodableValue?
        if container.contains(.body) {
            let bodyContainer = try container.superDecoder(forKey: .body)
            body = try AnyCodableValue(from: bodyContainer)
        } else {
            body = nil
        }

        self.init(
            name: name,
            referenceName: try container.decodeIfPresent(String.self, forKey: .referenceName),
            description: try container.decodeIfPresent(String.self, forKey: .description),
            isDefault: try container.decodeIfPresent(Bool.self, forKey: .isDefault),
            status: try container.decode(Int.self, forKey: .status),
            headers: try container.decodeIfPresent([String: String].self, forKey: .headers),
            requestMatch: try container.decodeIfPresent(RequestMatch.self, forKey: .requestMatch),
            body: body,
            bodyFile: try container.decodeIfPresent(String.self, forKey: .bodyFile),
            delayMs: try container.decodeIfPresent(Int.self, forKey: .delayMs)
        )
    }
}
