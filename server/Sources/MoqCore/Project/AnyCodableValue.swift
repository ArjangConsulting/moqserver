import Foundation

/// A type-erased Codable value for representing arbitrary YAML/JSON content (e.g. response bodies).
public enum AnyCodableValue: Sendable, Equatable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([AnyCodableValue])
    case object([String: AnyCodableValue])
}

extension AnyCodableValue: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if container.decodeNil() {
            self = .null
        } else if let bool = try? container.decode(Bool.self) {
            self = .bool(bool)
        } else if let int = try? container.decode(Int.self) {
            self = .int(int)
        } else if let double = try? container.decode(Double.self) {
            self = .double(double)
        } else if let string = try? container.decode(String.self) {
            self = .string(string)
        } else if let array = try? container.decode([AnyCodableValue].self) {
            self = .array(array)
        } else if let object = try? container.decode([String: AnyCodableValue].self) {
            self = .object(object)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported value type")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let value):
            try container.encode(value)
        case .int(let value):
            try container.encode(value)
        case .double(let value):
            try container.encode(value)
        case .string(let value):
            try container.encode(value)
        case .array(let value):
            try container.encode(value)
        case .object(let value):
            try container.encode(value)
        }
    }
}

extension AnyCodableValue {
    /// Convert to a Foundation object suitable for JSON serialization.
    public func toFoundation() -> Any {
        switch self {
        case .null:
            return NSNull()
        case .bool(let value):
            return value
        case .int(let value):
            return value
        case .double(let value):
            return value
        case .string(let value):
            return value
        case .array(let value):
            return value.map { $0.toFoundation() }
        case .object(let value):
            return value.mapValues { $0.toFoundation() }
        }
    }

    /// Convert to JSON Data.
    public func toJSONData(prettyPrinted: Bool = false) -> Data? {
        let obj = toFoundation()
        guard JSONSerialization.isValidJSONObject(obj) || obj is String || obj is NSNumber else {
            return nil
        }
        let isFragment = !JSONSerialization.isValidJSONObject(obj)
        var options: JSONSerialization.WritingOptions = prettyPrinted ? [.prettyPrinted, .sortedKeys] : [.sortedKeys]
        if isFragment {
            options.insert(.fragmentsAllowed)
        }
        return try? JSONSerialization.data(withJSONObject: obj, options: options)
    }
}
