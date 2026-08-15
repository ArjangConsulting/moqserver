import Foundation

/// How an inline `body` string is encoded in the YAML source.
///
/// This is deliberately orthogonal to the `Content-Type` header: `Content-Type` says what the
/// bytes *mean*, `body_encoding` says how they are *written* in the document. Content type stays
/// an open IANA registry that the format never enumerates; this is a small closed set describing
/// transport of the bytes into YAML, so adding a new media type never requires a format change.
public enum BodyEncoding: String, Codable, Sendable, Equatable, CaseIterable {
    /// The body string is the literal payload text. The default when `body_encoding` is absent.
    case utf8
    /// The body string is base64; the payload is the decoded bytes. Only valid on a string body.
    case base64
}

/// Failures resolving an inline `body` into response bytes.
public enum InlineBodyError: Error, CustomStringConvertible, Equatable, Sendable {
    /// `body_encoding: base64` was set on a body that is not a string.
    case encodingRequiresStringBody(BodyEncoding)
    /// A base64 body could not be decoded.
    case invalidBase64

    public var description: String {
        switch self {
        case .encodingRequiresStringBody(let encoding):
            return "body_encoding \"\(encoding.rawValue)\" requires body to be a string."
        case .invalidBase64:
            return "body is not valid base64."
        }
    }
}

/// The single implementation of "inline body value → response bytes".
///
/// Both fixture materialization (`ProjectStore.save`) and runtime conversion
/// (`ProjectToRuntimeConverter`) go through here. They previously disagreed — the store wrote a
/// string body as raw UTF-8 while the runtime served it JSON-quoted — which is exactly the class
/// of divergence this type exists to make impossible.
public enum InlineBody {
    /// Resolved bytes for an inline body, plus the file extension to use when materializing it
    /// into `fixtures/`.
    public struct Resolved: Equatable, Sendable {
        public let data: Data
        public let fileExtension: String

        public init(data: Data, fileExtension: String) {
            self.data = data
            self.fileExtension = fileExtension
        }
    }

    /// Resolves an inline body value under the given encoding.
    ///
    /// - A string body is the literal payload: raw UTF-8 bytes, never JSON-quoted.
    /// - Any other value is structured data and is serialized as JSON.
    /// - Under `.base64` the body must be a string, and decodes to arbitrary bytes.
    ///
    /// - Parameter prettyPrintStructured: whether JSON serialization of a structured body is
    ///   pretty-printed. Fixtures on disk are pretty-printed so they diff well; responses on the
    ///   wire are compact. This is the only legitimate difference between the two callers.
    /// - Parameter contentType: the variant's `Content-Type`, used only to pick a fixture file
    ///   extension. It never changes the bytes — that is `body_encoding`'s job alone.
    public static func resolve(
        _ body: AnyCodableValue,
        encoding: BodyEncoding?,
        contentType: String? = nil,
        prettyPrintStructured: Bool = false
    ) throws -> Resolved {
        switch encoding ?? .utf8 {
        case .base64:
            guard case .string(let text) = body else {
                throw InlineBodyError.encodingRequiresStringBody(.base64)
            }
            // `.ignoreUnknownCharacters` tolerates the line wrapping that YAML block scalars
            // introduce when a long base64 payload is written across multiple lines.
            guard let decoded = Data(base64Encoded: text, options: .ignoreUnknownCharacters) else {
                throw InlineBodyError.invalidBase64
            }
            return Resolved(data: decoded, fileExtension: fileExtension(for: contentType) ?? "bin")
        case .utf8:
            if case .string(let text) = body {
                return Resolved(
                    data: Data(text.utf8), fileExtension: fileExtension(for: contentType) ?? "txt")
            }
            let json = body.toJSONData(prettyPrinted: prettyPrintStructured) ?? Data("null".utf8)
            return Resolved(data: json, fileExtension: fileExtension(for: contentType) ?? "json")
        }
    }

    /// Fixture file extension for a media type, so a mocked PNG lands at `…-default.png` rather
    /// than an opaque `.bin`. Returns nil when there is no content type to go on, leaving the
    /// caller's shape-derived default in place.
    public static func fileExtension(for contentType: String?) -> String? {
        guard
            let mediaType = contentType?
                .split(separator: ";").first?
                .trimmingCharacters(in: .whitespaces)
                .lowercased(),
            !mediaType.isEmpty
        else { return nil }

        switch mediaType {
        case "application/json", "application/graphql-response+json": return "json"
        case "application/xml", "text/xml": return "xml"
        case "text/plain": return "txt"
        case "text/html": return "html"
        case "text/css": return "css"
        case "application/javascript", "text/javascript": return "js"
        case "application/graphql", "text/graphql": return "graphql"
        case "image/jpeg": return "jpg"
        case "image/png": return "png"
        case "image/gif": return "gif"
        case "image/webp": return "webp"
        case "image/svg+xml": return "svg"
        case "application/pdf": return "pdf"
        default: break
        }
        if mediaType.hasSuffix("+json") { return "json" }
        if mediaType.hasSuffix("+xml") { return "xml" }
        // e.g. "application/vnd.acme.thing" -> "thing", "application/zip" -> "zip"
        let subtype = mediaType.split(separator: "/").last.map(String.init) ?? ""
        let trailing = subtype.split(separator: "+").last.map(String.init) ?? ""
        return trailing.isEmpty ? nil : trailing
    }
}
