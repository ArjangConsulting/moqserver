import Foundation

/// Structured error response for all mock server HTTP errors.
public struct ErrorResponse: Codable, Sendable, Equatable {
    public let error: String
    public let code: String
    public let detail: String?
    public let hint: String?

    public init(error: String, code: String, detail: String? = nil, hint: String? = nil) {
        self.error = error
        self.code = code
        self.detail = detail
        self.hint = hint
    }

    /// Encodes this response as JSON Data.
    public func jsonData() -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(self)) ?? Data(#"{"error":"encoding_failed","code":"internal_error"}"#.utf8)
    }
}
