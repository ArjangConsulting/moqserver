import Foundation
import MCP
import MoqCore
import MoqFormat

/// Structured error payload every failing tool call returns as `structuredContent`, so a calling
/// agent can branch on `code` and `diagnostics` rather than parse prose.
struct ToolErrorPayload: Codable {
    let code: String
    let message: String
    let diagnostics: [DiagnosticPayload]?

    init(code: String, message: String, diagnostics: [DiagnosticPayload]? = nil) {
        self.code = code
        self.message = message
        self.diagnostics = diagnostics
    }
}

struct DiagnosticPayload: Codable {
    let severity: String
    let code: String?
    let message: String
    let file: String?
    let field: String?
    let endpointId: String?
    let variantName: String?

    enum CodingKeys: String, CodingKey {
        case severity, code, message, file, field
        case endpointId = "endpoint_id"
        case variantName = "variant_name"
    }

    init(_ diagnostic: ValidationDiagnostic) {
        severity = diagnostic.severity.rawValue
        code = diagnostic.code?.rawValue
        message = diagnostic.message
        file = diagnostic.file
        field = diagnostic.field
        endpointId = diagnostic.endpointID
        variantName = diagnostic.variantName
    }
}

/// Builds a `CallTool.Result` with `isError: true` and a structured error payload, plus a short
/// human-readable text summary for clients that don't render structured content.
func toolError(code: String, message: String, diagnostics: [ValidationDiagnostic] = []) throws -> CallTool.Result {
    let payload = ToolErrorPayload(
        code: code, message: message,
        diagnostics: diagnostics.isEmpty ? nil : diagnostics.map(DiagnosticPayload.init))
    return try CallTool.Result(
        content: [.text(text: "\(code): \(message)", annotations: nil, _meta: nil)],
        structuredContent: payload, isError: true)
}

func toolError(_ error: Error) throws -> CallTool.Result {
    switch error {
    case let error as SessionError:
        return try toolError(code: sessionErrorCode(error), message: error.description)
    case let error as ProjectStoreError:
        return try toolError(code: projectStoreErrorCode(error), message: error.description)
    case let error as ProjectLoadError:
        return try toolError(code: "E_LOAD_FAILED", message: error.description)
    default:
        return try toolError(code: "E_INTERNAL", message: "\(error)")
    }
}

private func sessionErrorCode(_ error: SessionError) -> String {
    switch error {
    case .noProjectOpen: return "E_NO_PROJECT_OPEN"
    case .unsavedChanges: return "E_UNSAVED_CHANGES"
    }
}

private func projectStoreErrorCode(_ error: ProjectStoreError) -> String {
    switch error {
    case .projectAlreadyExists: return "E_PROJECT_ALREADY_EXISTS"
    case .endpointNotFound: return "E_ENDPOINT_NOT_FOUND"
    case .endpointAlreadyExists: return "E_ENDPOINT_ALREADY_EXISTS"
    case .endpointIDMismatch: return "E_ENDPOINT_ID_MISMATCH"
    case .variantNotFound: return "E_VARIANT_NOT_FOUND"
    case .invalidEndpointID: return "E_INVALID_ENDPOINT_ID"
    case .invalidFixturePath: return "E_INVALID_FIXTURE_PATH"
    case .fixtureNotFound: return "E_FIXTURE_NOT_FOUND"
    case .projectChangedOnDisk: return "E_PROJECT_CHANGED"
    case .projectRecoveryRequired: return "E_PROJECT_RECOVERY_REQUIRED"
    }
}

/// Decodes MCP tool call arguments (`[String: Value]`) into any `Decodable` type by round-
/// tripping through JSON — the wire shape of `Value` and standard JSON are identical, and the
/// project format models (`EndpointDocument`, `ProjectVariant`, ...) already have the right
/// `CodingKeys` for this, so tool inputs decode directly into them with no separate DTO layer.
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
