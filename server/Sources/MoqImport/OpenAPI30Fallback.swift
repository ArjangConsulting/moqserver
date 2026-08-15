import OpenAPIKit
import OpenAPIKit30
import OpenAPIKitCompat
import Yams

/// Isolates the OpenAPI 3.0 decode-and-upconvert path in its own file. `OpenAPIKit` and
/// `OpenAPIKit30` both export identically-named top-level types (`OpenAPI`, `DereferencedContent`,
/// etc.), so any file importing both must fully qualify every reference to avoid ambiguity —
/// confined here, `OpenAPIImporter.swift` only needs to import `OpenAPIKit` (3.1) and can use
/// unqualified names throughout.
enum OpenAPI30Fallback {
    /// Attempts to decode `content` as an OpenAPI 3.0 document and upconvert it to 3.1.
    /// Returns `nil` if it doesn't parse as 3.0 either.
    static func decodeAndUpconvert(_ content: String) -> OpenAPIKit.OpenAPI.Document? {
        guard let document30 = try? YAMLDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: content) else {
            return nil
        }
        return document30.convert(to: OpenAPIKit.OpenAPI.Document.Version.v3_1_1)
    }

    /// The decode error `decodeAndUpconvert` swallowed, for diagnostics when neither 3.1 nor
    /// 3.0 decoding succeeded.
    static func decodeError(_ content: String) -> String {
        do {
            _ = try YAMLDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: content)
            return "unknown error"
        } catch {
            return "\(error)"
        }
    }
}
