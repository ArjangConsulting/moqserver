/// A request validation rule for matching headers or query parameters.
public struct RuleMatcher: Codable, Sendable, Equatable {
    /// The header or query parameter name.
    public let name: String
    /// Optional value pattern to match against.
    public let match: String?
    /// Whether this header/param is required.
    public let required: Bool?

    public init(name: String, match: String? = nil, required: Bool? = nil) {
        self.name = name
        self.match = match
        self.required = required
    }
}
