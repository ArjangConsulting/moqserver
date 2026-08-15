import Foundation
import MCP
import MoqCore
import MoqService

/// Structured error payload every failing tool call returns as `structuredContent`, so a calling
/// agent can branch on `code` rather than parse prose.
struct ToolErrorPayload: Codable {
    let code: String
    let message: String

    init(code: String, message: String) {
        self.code = code
        self.message = message
    }
}

/// Builds a `CallTool.Result` with `isError: true` and a structured error payload, plus a short
/// human-readable text summary for clients that don't render structured content.
func toolError(code: String, message: String) throws -> CallTool.Result {
    let payload = ToolErrorPayload(code: code, message: message)
    return try CallTool.Result(
        content: [.text(text: "\(code): \(message)", annotations: nil, _meta: nil)],
        structuredContent: payload, isError: true)
}

/// Every failure path in this adapter funnels through `MoqService`'s own error mapping — the
/// tool-level error code an agent sees and the JSON-RPC-level error code Studio sees are the same
/// mapping, applied by the same function, so the two transports can never disagree about what a
/// given failure is called.
func toolError(_ error: Error) throws -> CallTool.Result {
    let (code, message) = moqServiceErrorCode(error)
    return try toolError(code: code, message: message)
}

/// Decodes MCP tool call arguments (`[String: Value]`) into any `Decodable` type by round-
/// tripping through JSON — the wire shape of `Value` and standard JSON are identical, and
/// `MoqService`'s input types already have the right `CodingKeys` for this, so tool inputs decode
/// directly into them with no separate DTO layer.
func decodeArguments<T: Decodable>(_ type: T.Type, from arguments: [String: Value]?) throws -> T {
    guard let arguments else {
        throw MCPError.invalidParams("Missing arguments")
    }
    let data = try JSONEncoder().encode(Value.object(arguments))
    do {
        return try JSONDecoder().decode(T.self, from: data)
    } catch {
        throw MCPError.invalidParams("Invalid arguments: \(error)")
    }
}
