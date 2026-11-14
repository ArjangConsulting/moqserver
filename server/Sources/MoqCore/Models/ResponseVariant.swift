import Foundation

/// A single mock response variant for an endpoint.
public struct ResponseVariant: Sendable {
    /// Variant name used for selection via `X-Mock-Variant` header.
    public let name: String

    /// Stable code-friendly identifier used by Studio and automation.
    public let referenceName: String

    /// Whether this variant is marked as the default.
    public let isDefault: Bool

    /// HTTP status code.
    public let statusCode: HTTPStatusCode

    /// Response headers.
    public let headers: [(String, String)]

    /// Raw response body data.
    public let body: Data?

    /// Optional delay in seconds before responding.
    public let delay: TimeInterval?

    /// Optional request matching used to pick this variant.
    public let requestMatch: RequestMatch?

    /// Chunked delivery settings, when the body is streamed.
    public let stream: ResponseStream?

    /// 1-indexed call number this variant is scoped to. When set, this variant is only
    /// eligible on the Nth call to its endpoint. `nil` matches every call.
    public let callCount: Int?

    /// Whether `body` contains the `{{baseURL}}` template token, computed once at load time so
    /// the request path only scans bodies that need substitution.
    public let containsBaseURLToken: Bool

    /// The response-body template token replaced with the URL the client used to reach the server.
    public static let baseURLToken = Data("{{baseURL}}".utf8)

    public init(
        name: String,
        referenceName: String? = nil,
        isDefault: Bool = false,
        statusCode: HTTPStatusCode = .ok,
        headers: [(String, String)] = [("Content-Type", "application/json")],
        body: Data? = nil,
        delay: TimeInterval? = nil,
        requestMatch: RequestMatch? = nil,
        stream: ResponseStream? = nil,
        callCount: Int? = nil
    ) {
        self.name = name
        self.referenceName = referenceName ?? defaultReferenceNameForVariantName(name)
        self.isDefault = isDefault
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.delay = delay
        self.requestMatch = requestMatch
        self.stream = stream
        self.callCount = callCount
        self.containsBaseURLToken = body.map { $0.range(of: Self.baseURLToken) != nil } ?? false
    }

    /// The declared `Content-Type`, if any (case-insensitive header name).
    public var contentType: String? {
        headers.first(where: { $0.0.caseInsensitiveCompare("Content-Type") == .orderedSame })?.1
    }

    public func matchesIdentifier(_ identifier: String) -> Bool {
        name == identifier || referenceName == identifier
    }
}
