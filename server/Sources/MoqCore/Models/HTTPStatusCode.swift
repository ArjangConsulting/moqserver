import Foundation

/// Framework-agnostic HTTP status code representation.
public struct HTTPStatusCode: Hashable, Sendable, CustomStringConvertible {
    public let code: UInt

    public init(code: UInt) {
        self.code = code
    }

    public var description: String { "\(code)" }

    public static let ok = HTTPStatusCode(code: 200)
    public static let badRequest = HTTPStatusCode(code: 400)
    public static let unauthorized = HTTPStatusCode(code: 401)
    public static let forbidden = HTTPStatusCode(code: 403)
    public static let notFound = HTTPStatusCode(code: 404)
    public static let unsupportedMediaType = HTTPStatusCode(code: 415)
    public static let internalServerError = HTTPStatusCode(code: 500)
}
