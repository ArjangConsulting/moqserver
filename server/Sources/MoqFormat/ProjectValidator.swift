import Foundation
import Logging
import MoqCore

private let logger = Logger(label: "moqserver.format.ProjectValidator")

/// Validates a MoqProject against schema-level and semantic rules.
public struct ProjectValidator: ProjectValidating {
    public init() {}

    public func validate(_ project: MoqProject) -> [ValidationDiagnostic] {
        logger.info("Validating project '\(project.manifest.name)'")
        var diagnostics: [ValidationDiagnostic] = []
        var seenEndpointReferenceNames: [String: String] = [:]
        var seenRoutes: [String: String] = [:]

        // Rule 1: project.yml must exist (already enforced by loader, but validate version)
        if project.manifest.version != MoqFormatRules.formatVersion {
            diagnostics.append(
                .init(
                    severity: .error,
                    message:
                        "Unsupported format version: \"\(project.manifest.version)\". Expected \"\(MoqFormatRules.formatVersion)\".",
                    file: "project.yml",
                    field: "version",
                    code: .unsupportedVersion
                ))
        }

        // Rule 2: endpoints/ should contain at least one endpoint. This is a semantic
        // requirement, not a structural one — ProjectLoader permits an empty endpoints/
        // directory so a work-in-progress project can be saved and reopened.
        if project.endpoints.isEmpty {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "No endpoint files found in endpoints/.",
                    code: .noEndpoints
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
                        field: "id",
                        code: .duplicateEndpointID,
                        endpointID: endpoint.id
                    ))
            } else {
                seenIds[endpoint.id] = fileName
            }

            // Validate ID format
            if !MoqFormatRules.isValidEndpointID(endpoint.id) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint id \"\(endpoint.id)\" must be lowercase alphanumeric with hyphens.",
                        file: fileName,
                        field: "id",
                        code: .invalidEndpointID,
                        endpointID: endpoint.id
                    ))
            }

            if endpoint.referenceName.isEmpty {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint reference_name is required.",
                        file: fileName,
                        field: "reference_name",
                        code: .missingReferenceName,
                        endpointID: endpoint.id
                    ))
            } else if !isValidReferenceName(endpoint.referenceName) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message:
                            "Endpoint reference_name \"\(endpoint.referenceName)\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                        file: fileName,
                        field: "reference_name",
                        code: .invalidReferenceName,
                        endpointID: endpoint.id
                    ))
            } else if let existingReferenceNameFile = seenEndpointReferenceNames[endpoint.referenceName] {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message:
                            "Duplicate endpoint reference_name \"\(endpoint.referenceName)\" (also in \(existingReferenceNameFile)).",
                        file: fileName,
                        field: "reference_name",
                        code: .duplicateReferenceName,
                        endpointID: endpoint.id
                    ))
            } else {
                seenEndpointReferenceNames[endpoint.referenceName] = fileName
            }

            let normalizedPath = normalizePath(endpoint.path)

            // Rule 5: Reserved paths
            if MoqFormatRules.isReservedPath(normalizedPath) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Path \"\(endpoint.path)\" is reserved and cannot be used by mock endpoints.",
                        file: fileName,
                        field: "path",
                        code: .reservedPath,
                        endpointID: endpoint.id
                    ))
            }

            if endpoint.operation == nil {
                let routeKey = "\(endpoint.method.uppercased()) \(routeTemplateKey(normalizedPath))"
                if let existing = seenRoutes[routeKey] {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message:
                                "Duplicate REST route \"\(endpoint.method.uppercased()) \(endpoint.path)\" (equivalent to route in \(existing)).",
                            file: fileName,
                            field: "path",
                            code: .duplicateRoute,
                            endpointID: endpoint.id
                        ))
                } else {
                    seenRoutes[routeKey] = fileName
                }
            }

            // Rule 6: At least one variant
            if endpoint.variants.isEmpty {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Endpoint must have at least one variant.",
                        file: fileName,
                        field: "variants",
                        code: .noVariants,
                        endpointID: endpoint.id
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
                        field: "variants",
                        code: .multipleDefaultVariants,
                        endpointID: endpoint.id
                    ))
            }

            // Per-variant validation
            var seenVariantNames: Set<String> = []
            var seenVariantReferenceNames: Set<String> = []
            for (index, variant) in endpoint.variants.enumerated() {
                let variantField = "variants[\(index)]"

                if !(100...599).contains(variant.status) {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Variant status must be between 100 and 599.",
                            file: fileName,
                            field: "\(variantField).status",
                            code: .invalidVariantStatus,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                }

                if let delayMs = variant.delayMs, delayMs < 0 {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Variant delay_ms must be non-negative.",
                            file: fileName,
                            field: "\(variantField).delay_ms",
                            code: .invalidDelay,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                } else if let delayMs = variant.delayMs,
                    project.manifest.defaults.delayMs.addingReportingOverflow(delayMs).overflow
                {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Combined default and variant delay_ms exceeds the supported integer range.",
                            file: fileName,
                            field: "\(variantField).delay_ms",
                            code: .delayOverflow,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                }

                // Variant name uniqueness
                if seenVariantNames.contains(variant.name) {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Duplicate variant name \"\(variant.name)\".",
                            file: fileName,
                            field: "\(variantField).name",
                            code: .duplicateVariantName,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                }
                seenVariantNames.insert(variant.name)

                if variant.referenceName.isEmpty {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Variant reference_name is required.",
                            file: fileName,
                            field: "\(variantField).reference_name",
                            code: .missingVariantReferenceName,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                } else if !isValidReferenceName(variant.referenceName) {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message:
                                "Variant reference_name \"\(variant.referenceName)\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                            file: fileName,
                            field: "\(variantField).reference_name",
                            code: .invalidVariantReferenceName,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                } else if !seenVariantReferenceNames.insert(variant.referenceName).inserted {
                    diagnostics.append(
                        .init(
                            severity: .error,
                            message: "Duplicate variant reference_name \"\(variant.referenceName)\".",
                            file: fileName,
                            field: "\(variantField).reference_name",
                            code: .duplicateVariantReferenceName,
                            endpointID: endpoint.id,
                            variantName: variant.name
                        ))
                }

                if let requestMatch = variant.requestMatch {
                    if requestMatch.query.keys.contains(where: { $0.isEmpty }) {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "Variant request_match query names must not be blank.",
                                file: fileName,
                                field: "\(variantField).request_match.query",
                                code: .blankRequestMatchQueryName,
                                endpointID: endpoint.id,
                                variantName: variant.name
                            ))
                    }

                    if requestMatch.headers.keys.contains(where: { $0.isEmpty }) {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "Variant request_match header names must not be blank.",
                                file: fileName,
                                field: "\(variantField).request_match.headers",
                                code: .blankRequestMatchHeaderName,
                                endpointID: endpoint.id,
                                variantName: variant.name
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
                                field: "\(variantField).request_match",
                                code: .emptyRequestMatch,
                                endpointID: endpoint.id,
                                variantName: variant.name
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
                            field: variantField,
                            code: .bodyAndBodyFile,
                            endpointID: endpoint.id,
                            variantName: variant.name
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
                                field: "\(variantField).body_file",
                                code: .bodyFileMissingPrefix,
                                endpointID: endpoint.id,
                                variantName: variant.name
                            ))
                    } else if let fixtureURL = FixturePathResolver.resolve(
                        bodyFile: bodyFile,
                        projectPath: project.projectPath
                    ) {
                        var isDirectory: ObjCBool = false
                        if !FileManager.default.fileExists(atPath: fixtureURL.path, isDirectory: &isDirectory)
                            || isDirectory.boolValue
                        {
                            diagnostics.append(
                                .init(
                                    severity: .error,
                                    message: "Fixture file not found: \(bodyFile)",
                                    file: fileName,
                                    field: "\(variantField).body_file",
                                    code: .bodyFileNotFound,
                                    endpointID: endpoint.id,
                                    variantName: variant.name
                                ))
                        }
                    } else {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "body_file must resolve to a file inside the project's fixtures directory.",
                                file: fileName,
                                field: "\(variantField).body_file",
                                code: .bodyFileOutsideFixtures,
                                endpointID: endpoint.id,
                                variantName: variant.name
                            ))
                    }

                    // Reject path traversal
                    if bodyFile.contains("..") {
                        diagnostics.append(
                            .init(
                                severity: .error,
                                message: "body_file must not contain path traversal (..).",
                                file: fileName,
                                field: "\(variantField).body_file",
                                code: .bodyFilePathTraversal,
                                endpointID: endpoint.id,
                                variantName: variant.name
                            ))
                    }
                }
            }

            // Rule 10: auth.type validation
            if let auth = endpoint.auth {
                diagnostics.append(
                    contentsOf: validateAuth(auth, file: fileName, field: "auth", endpointID: endpoint.id))
            }

            diagnostics.append(
                contentsOf: validateNetwork(
                    endpoint.network, file: fileName, field: "network", endpointID: endpoint.id))

            // Rule 12: verify_cookies must be boolean (enforced by Codable, but check presence)
            // (Handled automatically by Codable decoding)

            // Rules 13-15: GraphQL validation
            if endpoint.path == "/graphql" || endpoint.operation != nil {
                diagnostics.append(contentsOf: validateGraphQL(endpoint, fileName: fileName))
            }

            // Validate HTTP method
            if !MoqFormatRules.isSupportedMethod(endpoint.method) {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Invalid HTTP method: \"\(endpoint.method)\".",
                        file: fileName,
                        field: "method",
                        code: .invalidMethod,
                        endpointID: endpoint.id
                    ))
            }

            // Path must start with /
            if !endpoint.path.hasPrefix("/") {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "Path must start with \"/\".",
                        file: fileName,
                        field: "path",
                        code: .invalidPathPrefix,
                        endpointID: endpoint.id
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
        diagnostics.append(
            contentsOf: validateNetwork(
                project.manifest.defaults.network,
                file: "project.yml",
                field: "defaults.network"
            ))
        if project.manifest.defaults.delayMs < 0 {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "Default delay_ms must be non-negative.",
                    file: "project.yml",
                    field: "defaults.delay_ms",
                    code: .invalidDefaultDelay
                ))
        }

        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }
        logger.info("Validation complete: \(errors.count) error(s), \(warnings.count) warning(s)")

        return diagnostics
    }

    // MARK: - Auth Validation

    private func validateAuth(
        _ auth: ProjectAuthConfig, file: String, field: String, endpointID: String? = nil
    ) -> [ValidationDiagnostic] {
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
                        field: "\(field).header_name",
                        code: .missingHeaderName,
                        endpointID: endpointID
                    ))
            }
        case .none, .bearer, .basic:
            break
        }

        return diagnostics
    }

    private func validateNetwork(
        _ network: NetworkBehavior?, file: String, field: String, endpointID: String? = nil
    ) -> [ValidationDiagnostic] {
        guard let network else { return [] }
        var diagnostics: [ValidationDiagnostic] = []

        if let latencyMs = network.latencyMs, latencyMs < 0 {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "latency_ms must be non-negative.",
                    file: file,
                    field: "\(field).latency_ms",
                    code: .invalidLatency,
                    endpointID: endpointID
                ))
        }
        if let jitterMs = network.jitterMs, jitterMs < 0 {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "jitter_ms must be non-negative.",
                    file: file,
                    field: "\(field).jitter_ms",
                    code: .invalidJitter,
                    endpointID: endpointID
                ))
        }
        if let packetLossPercent = network.packetLossPercent,
            !packetLossPercent.isFinite || !(0...100).contains(packetLossPercent)
        {
            diagnostics.append(
                .init(
                    severity: .error,
                    message: "packet_loss_percent must be finite and between 0 and 100.",
                    file: file,
                    field: "\(field).packet_loss_percent",
                    code: .invalidPacketLoss,
                    endpointID: endpointID
                ))
        }

        return diagnostics
    }

    private func normalizePath(_ path: String) -> String {
        let components = path.split(separator: "/", omittingEmptySubsequences: true)
        return components.isEmpty ? "/" : "/" + components.joined(separator: "/")
    }

    private func routeTemplateKey(_ path: String) -> String {
        path.split(separator: "/", omittingEmptySubsequences: true)
            .map { segment in
                segment.hasPrefix("{") && segment.hasSuffix("}") ? "{}" : String(segment)
            }
            .joined(separator: "/")
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
                    field: "operation",
                    code: .graphQLMissingOperation,
                    endpointID: endpoint.id
                ))
        }

        if let operation = endpoint.operation {
            if endpoint.path != "/graphql" {
                diagnostics.append(
                    .init(
                        severity: .warning,
                        message: "Endpoint has an operation but path is not /graphql.",
                        file: fileName,
                        field: "path",
                        code: .operationWithoutGraphQLPath,
                        endpointID: endpoint.id
                    ))
            }

            // Rule 14: At least one of name or document
            if operation.name == nil && operation.document == nil {
                diagnostics.append(
                    .init(
                        severity: .error,
                        message: "GraphQL operation must define at least one of \"name\" or \"document\".",
                        file: fileName,
                        field: "operation",
                        code: .graphQLOperationMissingNameOrDocument,
                        endpointID: endpoint.id
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
                            field: "operation.document",
                            code: .graphQLEmptyDocument,
                            endpointID: endpoint.id
                        ))
                }
            }
        }

        return diagnostics
    }
}
