/// Validates a MoqProject for correctness.
public protocol ProjectValidating: Sendable {
    func validate(_ project: MoqProject) -> [ValidationDiagnostic]
}

/// A validation diagnostic with file context.
public struct ValidationDiagnostic: Sendable, Equatable, CustomStringConvertible {
    public let severity: Severity
    public let message: String
    /// The file where the issue was found (e.g. "endpoints/list-users.yml").
    public let file: String?
    /// The specific field path (e.g. "variants[0].body_file").
    public let field: String?
    /// Stable machine-readable identifier for this diagnostic's rule. Callers (e.g. the MCP
    /// server) should branch on `code` rather than parse `message`, which is free to reword.
    public let code: DiagnosticCode?
    /// The endpoint id this diagnostic concerns, when applicable.
    public let endpointID: String?
    /// The variant name this diagnostic concerns, when applicable.
    public let variantName: String?

    public init(
        severity: Severity,
        message: String,
        file: String? = nil,
        field: String? = nil,
        code: DiagnosticCode? = nil,
        endpointID: String? = nil,
        variantName: String? = nil
    ) {
        self.severity = severity
        self.message = message
        self.file = file
        self.field = field
        self.code = code
        self.endpointID = endpointID
        self.variantName = variantName
    }

    public enum Severity: String, Sendable, Equatable {
        case error
        case warning
    }

    public var description: String {
        var parts: [String] = []
        parts.append("[\(severity.rawValue)]")
        if let file { parts.append(file) }
        if let field { parts.append("(\(field))") }
        parts.append(message)
        return parts.joined(separator: " ")
    }
}

/// Stable machine-readable identifiers for `ValidationDiagnostic` rules.
///
/// Prose in `ValidationDiagnostic.message` stays human-oriented and may be reworded; `code` is
/// the contract callers (notably the MCP server) branch on to let a calling agent self-correct
/// without parsing English.
public enum DiagnosticCode: String, Sendable, Equatable {
    case unsupportedVersion = "E_UNSUPPORTED_VERSION"
    case noEndpoints = "E_NO_ENDPOINTS"
    case duplicateEndpointID = "E_DUPLICATE_ENDPOINT_ID"
    case invalidEndpointID = "E_INVALID_ENDPOINT_ID"
    case missingReferenceName = "E_MISSING_REFERENCE_NAME"
    case invalidReferenceName = "E_INVALID_REFERENCE_NAME"
    case duplicateReferenceName = "E_DUPLICATE_REFERENCE_NAME"
    case reservedPath = "E_RESERVED_PATH"
    case duplicateRoute = "E_DUPLICATE_ROUTE"
    case noVariants = "E_NO_VARIANTS"
    case multipleDefaultVariants = "E_MULTIPLE_DEFAULT_VARIANTS"
    case invalidVariantStatus = "E_INVALID_VARIANT_STATUS"
    case invalidDelay = "E_INVALID_DELAY"
    case delayOverflow = "E_DELAY_OVERFLOW"
    case duplicateVariantName = "E_DUPLICATE_VARIANT_NAME"
    case missingVariantReferenceName = "E_MISSING_VARIANT_REFERENCE_NAME"
    case invalidVariantReferenceName = "E_INVALID_VARIANT_REFERENCE_NAME"
    case duplicateVariantReferenceName = "E_DUPLICATE_VARIANT_REFERENCE_NAME"
    case blankRequestMatchQueryName = "E_BLANK_REQUEST_MATCH_QUERY_NAME"
    case blankRequestMatchHeaderName = "E_BLANK_REQUEST_MATCH_HEADER_NAME"
    case emptyRequestMatch = "E_EMPTY_REQUEST_MATCH"
    case bodyAndBodyFile = "E_BODY_AND_BODY_FILE"
    case bodyFileMissingPrefix = "E_BODY_FILE_MISSING_PREFIX"
    case bodyFileNotFound = "E_BODY_FILE_NOT_FOUND"
    case bodyFileOutsideFixtures = "E_BODY_FILE_OUTSIDE_FIXTURES"
    case bodyFilePathTraversal = "E_BODY_FILE_PATH_TRAVERSAL"
    case missingHeaderName = "E_MISSING_HEADER_NAME"
    case invalidLatency = "E_INVALID_LATENCY"
    case invalidJitter = "E_INVALID_JITTER"
    case invalidPacketLoss = "E_INVALID_PACKET_LOSS"
    case graphQLMissingOperation = "E_GRAPHQL_MISSING_OPERATION"
    case operationWithoutGraphQLPath = "W_OPERATION_WITHOUT_GRAPHQL_PATH"
    case graphQLOperationMissingNameOrDocument = "E_GRAPHQL_OPERATION_MISSING_NAME_OR_DOCUMENT"
    case graphQLEmptyDocument = "E_GRAPHQL_EMPTY_DOCUMENT"
    case invalidMethod = "E_INVALID_METHOD"
    case invalidPathPrefix = "E_INVALID_PATH_PREFIX"
    case invalidDefaultDelay = "E_INVALID_DEFAULT_DELAY"
}
