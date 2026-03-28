import Foundation
import MoqCore
import OpenAPIKit
import OpenAPIKit30
import OpenAPIKitCompat
import Yams

/// Validates an OpenAPI spec for compliance and mock-readiness.
/// Reports both structural issues and warnings about mock quality.
public struct OpenAPISpecValidator: Sendable {
    public init() {}

    /// Validates raw spec data and returns diagnostics.
    public func validate(data: Data) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        // Try to parse the document
        let document: OpenAPIKit.OpenAPI.Document
        do {
            document = try parseDocument(data: data)
        } catch {
            diagnostics.append(.init(
                severity: .error,
                message: "Failed to parse OpenAPI spec: \(error)"
            ))
            return diagnostics
        }

        diagnostics.append(contentsOf: validateInfo(document))
        diagnostics.append(contentsOf: validatePaths(document))
        diagnostics.append(contentsOf: validateSecuritySchemes(document))
        diagnostics.append(contentsOf: validateComponents(document))

        return diagnostics
    }

    // MARK: - Document Parsing

    private func parseDocument(data: Data) throws -> OpenAPIKit.OpenAPI.Document {
        if let doc = try? JSONDecoder().decode(OpenAPIKit.OpenAPI.Document.self, from: data) {
            return doc
        }
        if let doc = try? YAMLDecoder().decode(OpenAPIKit.OpenAPI.Document.self, from: data) {
            return doc
        }
        if let doc30 = try? JSONDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: data) {
            return doc30.convert(to: .v3_1_0)
        }
        if let doc30 = try? YAMLDecoder().decode(OpenAPIKit30.OpenAPI.Document.self, from: data) {
            return doc30.convert(to: .v3_1_0)
        }
        throw OpenAPIParserError.unsupportedFormat
    }

    // MARK: - Info Validation

    private func validateInfo(_ document: OpenAPIKit.OpenAPI.Document) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        if document.info.title.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            diagnostics.append(.init(
                severity: .warning,
                message: "Spec title is empty.",
                field: "info.title"
            ))
        }

        if document.info.version.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            diagnostics.append(.init(
                severity: .warning,
                message: "Spec version is empty.",
                field: "info.version"
            ))
        }

        return diagnostics
    }

    // MARK: - Paths Validation

    private func validatePaths(_ document: OpenAPIKit.OpenAPI.Document) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        if document.paths.isEmpty {
            diagnostics.append(.init(
                severity: .error,
                message: "Spec defines no paths."
            ))
            return diagnostics
        }

        var seenOperationIds: [String: String] = [:]

        for (path, pathItemEither) in document.paths {
            let pathString = path.rawValue

            guard let pathItem = pathItemEither.pathItemValue else {
                diagnostics.append(.init(
                    severity: .warning,
                    message: "Unresolvable $ref for path \"\(pathString)\".",
                    file: pathString
                ))
                continue
            }

            if pathItem.endpoints.isEmpty {
                diagnostics.append(.init(
                    severity: .warning,
                    message: "Path \"\(pathString)\" has no operations.",
                    file: pathString
                ))
            }

            for endpoint in pathItem.endpoints {
                let method = endpoint.method.rawValue.uppercased()
                let operation = endpoint.operation
                let opLabel = "\(method) \(pathString)"

                // Operation ID uniqueness
                if let opId = operation.operationId {
                    if let existing = seenOperationIds[opId] {
                        diagnostics.append(.init(
                            severity: .error,
                            message: "Duplicate operationId \"\(opId)\" (also used by \(existing)).",
                            file: pathString,
                            field: "operationId"
                        ))
                    } else {
                        seenOperationIds[opId] = opLabel
                    }
                } else {
                    diagnostics.append(.init(
                        severity: .warning,
                        message: "\(opLabel) has no operationId.",
                        file: pathString,
                        field: "operationId"
                    ))
                }

                // Deprecated check
                if operation.deprecated {
                    diagnostics.append(.init(
                        severity: .warning,
                        message: "\(opLabel) is marked as deprecated.",
                        file: pathString
                    ))
                }

                // Response validation
                diagnostics.append(contentsOf: validateOperationResponses(
                    operation: operation,
                    opLabel: opLabel,
                    pathString: pathString,
                    method: method
                ))

                // Security references
                if let security = operation.security {
                    diagnostics.append(contentsOf: validateSecurityReferences(
                        security: security,
                        document: document,
                        opLabel: opLabel,
                        pathString: pathString
                    ))
                }
            }
        }

        return diagnostics
    }

    private func validateOperationResponses(
        operation: OpenAPIKit.OpenAPI.Operation,
        opLabel: String,
        pathString: String,
        method: String
    ) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        if operation.responses.isEmpty {
            diagnostics.append(.init(
                severity: .error,
                message: "\(opLabel) has no responses defined.",
                file: pathString,
                field: "responses"
            ))
            return diagnostics
        }

        // Check for success response
        let hasSuccess = operation.responses.contains { statusCode, _ in
            switch statusCode.value {
            case .status(let code): return code >= 200 && code < 300
            case .default: return true
            case .range(let range): return range == .success
            }
        }
        if !hasSuccess {
            diagnostics.append(.init(
                severity: .warning,
                message: "\(opLabel) has no success (2xx) response defined.",
                file: pathString,
                field: "responses"
            ))
        }

        // Check response content for mock quality
        for (statusCode, responseEither) in operation.responses {
            let statusLabel: String
            switch statusCode.value {
            case .status(let code): statusLabel = "\(code)"
            case .default: statusLabel = "default"
            case .range(let range): statusLabel = range.rawValue
            }

            guard let response = responseEither.responseValue else {
                diagnostics.append(.init(
                    severity: .warning,
                    message: "\(opLabel) response \(statusLabel): unresolvable $ref.",
                    file: pathString,
                    field: "responses.\(statusLabel)"
                ))
                continue
            }

            // Warn about empty content for GET/POST success responses
            let isSuccessRange = statusCode.value == .default
                || { if case .status(let c) = statusCode.value { return c >= 200 && c < 300 } else { return false } }()
                || { if case .range(.success) = statusCode.value { return true } else { return false } }()

            if response.content.isEmpty && isSuccessRange && (method == "GET" || method == "POST") {
                diagnostics.append(.init(
                    severity: .warning,
                    message: "\(opLabel) response \(statusLabel) has no content — mock will return an empty body.",
                    file: pathString,
                    field: "responses.\(statusLabel).content"
                ))
            }

            // Check for missing examples (mock quality)
            for (contentType, contentEither) in response.content {
                guard let content = contentEither.contentValue else { continue }
                if content.example == nil && content.schema != nil {
                    diagnostics.append(.init(
                        severity: .warning,
                        message: "\(opLabel) response \(statusLabel) (\(contentType.rawValue)) has a schema but no example — mock will use auto-generated stub data.",
                        file: pathString,
                        field: "responses.\(statusLabel).content.\(contentType.rawValue).example"
                    ))
                }
                if content.example == nil && content.schema == nil {
                    diagnostics.append(.init(
                        severity: .warning,
                        message: "\(opLabel) response \(statusLabel) (\(contentType.rawValue)) has neither example nor schema — mock body will be a generic placeholder.",
                        file: pathString,
                        field: "responses.\(statusLabel).content.\(contentType.rawValue)"
                    ))
                }
            }
        }

        return diagnostics
    }

    // MARK: - Security Validation

    private func validateSecuritySchemes(_ document: OpenAPIKit.OpenAPI.Document) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        // Validate global security references
        diagnostics.append(contentsOf: validateSecurityReferences(
            security: document.security,
            document: document,
            opLabel: "global",
            pathString: "security"
        ))

        return diagnostics
    }

    private func validateSecurityReferences(
        security: [OpenAPIKit.OpenAPI.SecurityRequirement],
        document: OpenAPIKit.OpenAPI.Document,
        opLabel: String,
        pathString: String
    ) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        for requirement in security {
            for schemeRef in requirement.keys {
                guard let schemeName = schemeRef.name else { continue }
                let key = OpenAPIKit.OpenAPI.ComponentKey(rawValue: schemeName)
                let exists = key.flatMap { document.components.securitySchemes[$0] } != nil
                if !exists {
                    diagnostics.append(.init(
                        severity: .error,
                        message: "\(opLabel) references undefined security scheme \"\(schemeName)\".",
                        file: pathString,
                        field: "security"
                    ))
                }
            }
        }

        return diagnostics
    }

    // MARK: - Components Validation

    private func validateComponents(_ document: OpenAPIKit.OpenAPI.Document) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        // Check for schemas referenced but not defined
        // OpenAPIKit resolves $refs during parsing, so unresolvable refs would
        // already cause parse errors. We can still check for unused schemas as info.
        let schemaCount = document.components.schemas.count
        if schemaCount > 50 {
            diagnostics.append(.init(
                severity: .warning,
                message: "Spec defines \(schemaCount) component schemas — large specs may increase startup time.",
                field: "components.schemas"
            ))
        }

        return diagnostics
    }
}
