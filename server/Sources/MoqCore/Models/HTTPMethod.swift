import Foundation

/// Framework-agnostic HTTP method representation.
public struct HTTPMethodValue: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue.uppercased()
    }

    public var description: String { rawValue }

    public static let get = HTTPMethodValue(rawValue: "GET")
    public static let post = HTTPMethodValue(rawValue: "POST")
    public static let put = HTTPMethodValue(rawValue: "PUT")
    public static let patch = HTTPMethodValue(rawValue: "PATCH")
    public static let delete = HTTPMethodValue(rawValue: "DELETE")
    public static let head = HTTPMethodValue(rawValue: "HEAD")
    public static let options = HTTPMethodValue(rawValue: "OPTIONS")
    public static let trace = HTTPMethodValue(rawValue: "TRACE")
    public static let connect = HTTPMethodValue(rawValue: "CONNECT")
}
