import Foundation

private let generatedVariantNamePattern = "^(default|success(?:[-_]\\d+)?|error(?:[-_]\\d+)?)$"

/// A generic display-name fallback derived purely from a variant's status code.
public func defaultVariantBaseName(status: Int) -> String {
    switch status {
    case 200...299: return "Success"
    case 400...599: return "Error"
    default: return "Variant"
    }
}

/// Derives a unique variant display name. `preferredName` is used as-is unless it's blank or
/// looks like a name this function itself would generate (e.g. "default", "success-2"), in which
/// case it's replaced with a fresh status-derived base name — this keeps re-imports from
/// accumulating "default", "default 2", "default 3", ... Appends a numeric suffix on collision.
public func suggestedVariantName(
    status: Int,
    existingNames: [String] = [],
    preferredName: String? = nil
) -> String {
    let trimmedPreferred = preferredName?.trimmingCharacters(in: .whitespacesAndNewlines)
    let rawName: String
    if let trimmedPreferred, !trimmedPreferred.isEmpty {
        if trimmedPreferred.range(
            of: generatedVariantNamePattern, options: [.regularExpression, .caseInsensitive]) != nil
        {
            rawName = defaultVariantBaseName(status: status)
        } else {
            rawName = trimmedPreferred
        }
    } else {
        rawName = defaultVariantBaseName(status: status)
    }

    guard existingNames.contains(rawName) else { return rawName }
    var suffix = 2
    while true {
        let candidate = "\(rawName) \(suffix)"
        if !existingNames.contains(candidate) { return candidate }
        suffix += 1
    }
}
