import Foundation

/// A JSON-RPC 2.0 request id: string, number, or (for a notification) absent. `moq-format` never
/// sends notifications itself, but must still echo back whatever id shape the client used.
enum JSONRPCID: Codable, Equatable {
    case string(String)
    case number(Double)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            self = .string(value)
        } else {
            self = .number(try container.decode(Double.self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        }
    }
}

struct JSONRPCRequest: Decodable {
    let jsonrpc: String
    let id: JSONRPCID?
    let method: String
    let params: Data?

    private enum CodingKeys: String, CodingKey { case jsonrpc, id, method, params }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        jsonrpc = try container.decodeIfPresent(String.self, forKey: .jsonrpc) ?? "2.0"
        id = try container.decodeIfPresent(JSONRPCID.self, forKey: .id)
        method = try container.decode(String.self, forKey: .method)
        // Re-serialize params to Data so a handler can decode it into its own typed input,
        // without this envelope needing to know every method's parameter shape up front.
        if container.contains(.params) {
            let paramsValue = try container.decode(AnyDecodableBox.self, forKey: .params)
            params = try JSONEncoder().encode(paramsValue)
        } else {
            params = nil
        }
    }
}

struct JSONRPCErrorObject: Codable {
    let code: Int
    let message: String
    let data: JSONRPCErrorData?
}

struct JSONRPCErrorData: Codable {
    let code: String
}

struct JSONRPCResponse: Encodable {
    let jsonrpc = "2.0"
    let id: JSONRPCID?
    let resultData: Data?
    let error: JSONRPCErrorObject?

    private enum CodingKeys: String, CodingKey { case jsonrpc, id, result, error }

    init(id: JSONRPCID?, resultData: Data?) {
        self.id = id
        self.resultData = resultData
        self.error = nil
    }

    init(id: JSONRPCID?, error: JSONRPCErrorObject) {
        self.id = id
        self.resultData = nil
        self.error = error
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(jsonrpc, forKey: .jsonrpc)
        try container.encode(id, forKey: .id)
        if let error {
            try container.encode(error, forKey: .error)
        } else if let resultData {
            let box = try JSONDecoder().decode(AnyDecodableBox.self, from: resultData)
            try container.encode(box, forKey: .result)
        } else {
            try container.encodeNil(forKey: .result)
        }
    }
}

/// A type-erased JSON value used only to pass `params`/`result` payloads through this envelope
/// unparsed, so `JSONRPCRequest`/`JSONRPCResponse` don't need to know every method's shape.
enum AnyDecodableBox: Codable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([AnyDecodableBox])
    case object([String: AnyDecodableBox])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([AnyDecodableBox].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: AnyDecodableBox].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}
