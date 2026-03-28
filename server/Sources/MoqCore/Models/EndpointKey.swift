import Foundation

/// Uniquely identifies an endpoint by HTTP method and path pattern.
public struct EndpointKey: Hashable, Sendable {
    public let method: HTTPMethodValue
    public let path: String

    public init(method: HTTPMethodValue, path: String) {
        self.method = method
        self.path = path.hasPrefix("/") ? path : "/\(path)"
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(method.rawValue)
        hasher.combine(path)
    }

    public static func == (lhs: EndpointKey, rhs: EndpointKey) -> Bool {
        lhs.method == rhs.method && lhs.path == rhs.path
    }
}
