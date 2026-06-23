import Foundation
import Logging
import MoqCore

private let logger = Logger(label: "moqserver.format.ProjectValidator")

/// Validates a MoqProject against schema-level and semantic rules.
public struct ProjectValidator: ProjectValidating {
    private let reservedPaths: Set<String> = ["/health", "/__admin/endpoints"]

    public init() {}

    public func validate(_ project: MoqProject) -> [ValidationDiagnostic] {
        logger.info("Validating project '\(project.manifest.name)'")
        var diagnostics: [ValidationDiagnostic] = []
        var seenEndpointReferenceNames: [String: String] = [:]

        // Rule 1: project.yml must exist (already enforced by loader, but validate version)
        if project.manifest.version != "1" {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "Unsupported format version: \"\(project.manifest.version)\". Expected \"1\".",
                    file: "project.yml",
                    field: "version"
                ))
        }

        // Rule 2: endpoints/ must contain at least one file (enforced by loader, redundant check)
        if project.endpoints.isEmpty {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "No endpoint files found in endpoints/."
                ))
        }

        // Rule 4: Endpoint IDs must be unique across the project
        var seenIds: [String: String] = [:]
        for endpoint in project.endpoints {
            let fileName = "endpoints/\(endpoint.id).yml"
            if let existing = seenIds[endpoint.id] {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Duplicate endpoint id \"\(endpoint.id)\" (also in \(existing)).",
                        file: fileName,
                        field: "id"
                    ))
            } else {
                seenIds[endpoint.id] = fileName
            }

            // Validate ID format
            if endpoint.id.range(of: "^[a-z0-9][a-z0-9-]*$", options: .regularExpression) == nil {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint id \"\(endpoint.id)\" must be lowercase alphanumeric with hyphens.",
                        file: fileName,
                        field: "id"
                    ))
            }

            if endpoint.referenceName.isEmpty {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint reference_name is required.",
                        file: fileName,
                        field: "reference_name"
                    ))
            } else if !isValidReferenceName(endpoint.referenceName) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message:
                            "Endpoint reference_name \"\(endpoint.referenceName)\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                        file: fileName,
                        field: "reference_name"
                    ))
            } else if let existingReferenceNameFile = seenEndpointReferenceNames[endpoint.referenceName] {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message:
                            "Duplicate endpoint reference_name \"\(endpoint.referenceName)\" (also in \(existingReferenceNameFile)).",
                        file: fileName,
                        field: "reference_name"
                    ))
            } else {
                seenEndpointReferenceNames[endpoint.referenceName] = fileName
            }

            // Rule 5: Reserved paths
            if reservedPaths.contains(endpoint.path) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Path \"\(endpoint.path)\" is reserved and cannot be used by mock endpoints.",
                        file: fileName,
                        field: "path"
                    ))
            }

            // Rule 6: At least one variant
            if endpoint.variants.isEmpty {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint must have at least one variant.",
                        file: fileName,
                        field: "variants"
                    ))
            }

            // Rule 7: At most one default variant
            let defaultCount = endpoint.variants.filter { $0.isDefault == true }.count
            if defaultCount > 1 {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Only one variant may be marked as default (\(defaultCount) found).",
                        file: fileName,
                        field: "variants"
                    ))
            }

            // Per-variant validation
            var seenVariantNames: Set<String> = []
            var seenVariantReferenceNames: Set<String> = []
            for (index, variant) in endpoint.variants.enumerated() {
                let variantField = "variants[\(index)]"

                // Variant name uniqueness
                if seenVariantNames.contains(variant.name) {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Duplicate variant name \"\(variant.name)\".",
                            file: fileName,
                            field: "\(variantField).name"
                        ))
                }
                seenVariantNames.insert(variant.name)

                if variant.referenceName.isEmpty {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Variant reference_name is required.",
                            file: fileName,
                            field: "\(variantField).reference_name"
                        ))
                } else if !isValidReferenceName(variant.referenceName) {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message:
                                "Variant reference_name \"\(variant.referenceName)\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                            file: fileName,
                            field: "\(variantField).reference_name"
                        ))
                } else if !seenVariantReferenceNames.insert(variant.referenceName).inserted {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Duplicate variant reference_name \"\(variant.referenceName)\".",
                            file: fileName,
                            field: "\(variantField).reference_name"
                        ))
                }

                if let requestMatch = variant.requestMatch {
                    if requestMatch.query.keys.contains(where: { $0.isEmpty }) {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "Variant request_match query names must not be blank.",
                                file: fileName,
                                field: "\(variantField).request_match.query"
                            ))
                    }

                    if requestMatch.headers.keys.contains(where: { $0.isEmpty }) {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "Variant request_match header names must not be blank.",
                                file: fileName,
                                field: "\(variantField).request_match.headers"
                            ))
                    }

                    if requestMatch.query.isEmpty && requestMatch.headers.isEmpty
                        && (requestMatch.bodyContains?.isEmpty ?? true)
                    {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "Variant request_match must define query, headers, or body_contains.",
                                file: fileName,
                                field: "\(variantField).request_match"
                            ))
                    }
                }

                // Rule 8: body and body_file are mutually exclusive
                if variant.body != nil && variant.bodyFile != nil {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message:
                                "Variant \"\(variant.name)\" defines both body and body_file. Only one is allowed.",
                            file: fileName,
                            field: variantField
                        ))
                }

                // Rule 9: body_file must point to fixtures/
                if let bodyFile = variant.bodyFile {
                    if !bodyFile.hasPrefix("fixtures/") {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "body_file \"\(bodyFile)\" must start with \"fixtures/\".",
                                file: fileName,
                                field: "\(variantField).body_file"
                            ))
                    } else {
                        // Check that the fixture file exists
                        let fixturePath = (project.projectPath as NSString).appendingPathComponent(bodyFile)
                        if !FileManager.default.fileExists(atPath: fixturePath) {
                            diagnostics.append(
                                .init(
                                    severity: .error,
                                    message: "Fixture file not found: \(bodyFile)",
                                    file: fileName,
                                    field: "\(variantField).body_file"
                                ))
                        }
                    }

                    // Reject path traversal
                    if bodyFile.contains("..") {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "body_file must not contain path traversal (..).",
                                file: fileName,
                                field: "\(variantField).body_file"
                            ))
                    }
                }
            }

            // Rule 10: auth.type validation
            if let auth = endpoint.auth {
                diagnostics.append(contentsOf: validateAuth(auth, file: fileName, field: "auth"))
            }

            // Rule 12: verify_cookies must be boolean (enforced by Codable, but check presence)
            // (Handled automatically by Codable decoding)

            // Rules 13-15: GraphQL validation
            if endpoint.path == "/graphql" || endpoint.operation != nil {
                diagnostics.append(contentsOf: validateGraphQL(endpoint, fileName: fileName))
            }

            // Validate HTTP method
            let validMethods: Set<String> = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]
            if !validMethods.contains(endpoint.method.uppercased()) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Invalid HTTP method: \"\(endpoint.method)\".",
                        file: fileName,
                        field: "method"
                    ))
            }

            // Path must start with /
            if !endpoint.path.hasPrefix("/") {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Path must start with \"/\".",
                        file: fileName,
                        field: "path"
                    ))
            }
        }

        // Validate project-level auth
        diagnostics.append(
            contentsOf: validateAuth(
                project.manifest.defaults.auth,
                file: "project.yml",
                field: "defaults.auth"
            ))

        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }
        logger.info("Validation complete: \(errors.count) error(s), \(warnings.count) warning(s)")

        return diagnostics
    }

    // MARK: - Auth Validation

    private func validateAuth(_ auth: ProjectAuthConfig, file: String, field: String) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        // Rule 11: header_name required for api-key and header types
        switch auth.type {
        case .apiKey, .header:
            if auth.headerName == nil || auth.headerName?.isEmpty == true {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "header_name is required when auth type is \"\(auth.type.rawValue)\".",
                        file: file,
                        field: "\(field).header_name"
                    ))
            }
        case .none, .bearer, .basic:
            break
        }

        return diagnostics
    }

    // MARK: - GraphQL Validation

    private func validateGraphQL(_ endpoint: EndpointDocument, fileName: String) -> [ValidationDiagnostic] {
        var diagnostics: [ValidationDiagnostic] = []

        // Rule 13: GraphQL endpoints must have path /graphql and operation.type
        if endpoint.path == "/graphql" && endpoint.operation == nil {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "GraphQL endpoints (path=/graphql) must define an operation.",
                    file: fileName,
                    field: "operation"
                ))
        }

        if let operation = endpoint.operation {
            if endpoint.path != "/graphql" {
                diagnostics.append(
                    .init(
                        severity: .warning,
                        message: "Endpoint has an operation but path is not /graphql.",
                        file: fileName,
                        field: "path"
                    ))
            }

            // Rule 14: At least one of name or document
            if operation.name == nil && operation.document == nil {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "GraphQL operation must define at least one of \"name\" or \"document\".",
                        file: fileName,
                        field: "operation"
                    ))
            }

            // Rule 15: document must be non-empty after normalization
            if let document = operation.document {
                let trimmed = document.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "GraphQL operation document must be non-empty after normalization.",
                            file: fileName,
                            field: "operation.document"
                        ))
                }
            }
        }

        return diagnostics
    }
}
