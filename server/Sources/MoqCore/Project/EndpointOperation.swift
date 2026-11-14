import Foundation

/// GraphQL operation matching configuration.
public struct EndpointOperation: Codable, Sendable, Equatable {
    /// The GraphQL operation type.
    public let type: OperationType
    /// Named operation (e.g. "GetUserProfile").
    public let name: String?
    /// Normalized query document for anonymous operations.
    public let document: String?

    public init(type: OperationType, name: String? = nil, document: String? = nil) {
        self.type = type
        self.name = name
        self.document = document
    }

    public enum OperationType: String, Codable, Sendable, Equatable {
        case query
        case mutation
        case subscription
    }

    /// Addresses this operation within its shared route: the operation name when set, otherwise
    /// `<type>:<hash of the normalized document>` (stable across runs), otherwise the type alone.
    public var stateSelector: String {
        if let name, !name.isEmpty { return name }
        guard let document, !document.isEmpty else { return type.rawValue }
        return "\(type.rawValue):\(Self.stableHash(Self.normalizeDocument(document)))"
    }

    /// FNV-1a 64-bit, hex-encoded. `Hasher` is seeded per process, so it can't name state that
    /// clients address across restarts.
    static func stableHash(_ text: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        let hex = String(hash, radix: 16)
        return String(repeating: "0", count: 16 - hex.count) + hex
    }

    /// Collapses all whitespace runs to single spaces so documents can be
    /// compared independently of formatting.
    public static func normalizeDocument(_ document: String) -> String {
        document
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
