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

    public init(severity: Severity, message: String, file: String? = nil, field: String? = nil) {
        self.severity = severity
        self.message = message
        self.file = file
        self.field = field
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
