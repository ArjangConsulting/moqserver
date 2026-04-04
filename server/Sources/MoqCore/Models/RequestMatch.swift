import Foundation

/// Additional request matching criteria for selecting a variant.
public struct RequestMatch: Codable, Equatable, Sendable {
    public let query: [String: String]
    public let headers: [String: String]
    public let bodyContains: String?

    enum CodingKeys: String, CodingKey {
        case query
        case headers
        case bodyContains = "body_contains"
    }

    public init(query: [String: String] = [:], headers: [String: String] = [:], bodyContains: String? = nil) {
        self.query = query
        self.headers = headers
        self.bodyContains = bodyContains
    }
}
