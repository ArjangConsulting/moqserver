import Foundation

public func isValidReferenceName(_ value: String) -> Bool {
    value.range(of: "^[A-Za-z_][A-Za-z0-9_]*$", options: .regularExpression) != nil
}

public func defaultReferenceNameForEndpointId(_ id: String) -> String {
    toReferenceName(id, fallbackPrefix: "endpoint")
}

public func defaultReferenceNameForVariantName(_ name: String) -> String {
    toReferenceName(name, fallbackPrefix: "variant")
}

/// Derives a unique endpoint `reference_name`, preferring `preferredSource` (e.g. an alias) over
/// `fallbackID`, and appending a numeric suffix if the derived name collides with `existingNames`.
public func suggestedEndpointReferenceName(
    preferredSource: String?,
    fallbackID: String,
    existingNames: [String] = []
) -> String {
    let baseName: String
    if let source = preferredSource?.trimmingCharacters(in: .whitespacesAndNewlines), !source.isEmpty {
        baseName = toReferenceName(source, fallbackPrefix: "endpoint")
    } else {
        baseName = defaultReferenceNameForEndpointId(fallbackID)
    }
    return uniqueReferenceName(baseName, existingNames)
}

/// Derives a unique variant `reference_name`, preferring `preferredSource` (e.g. the variant's
/// display name) over a status-code-derived fallback, appending a numeric suffix on collision.
public func suggestedVariantReferenceName(
    preferredSource: String?,
    status: Int,
    existingNames: [String] = []
) -> String {
    let source: String
    if let preferred = preferredSource?.trimmingCharacters(in: .whitespacesAndNewlines), !preferred.isEmpty {
        source = preferred
    } else {
        source = defaultVariantBaseName(status: status)
    }
    let baseName = toReferenceName(source, fallbackPrefix: "variant")
    return uniqueReferenceName(baseName, existingNames)
}

private func uniqueReferenceName(_ baseName: String, _ existingNames: [String]) -> String {
    guard existingNames.contains(baseName) else { return baseName }
    var suffix = 2
    while true {
        let candidate = "\(baseName)\(suffix)"
        if !existingNames.contains(candidate) { return candidate }
        suffix += 1
    }
}

private func toReferenceName(_ source: String, fallbackPrefix: String) -> String {
    let normalizedSource =
        source
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(
            of: "([a-z0-9])([A-Z])",
            with: "$1 $2",
            options: .regularExpression
        )
        .replacingOccurrences(of: "[^A-Za-z0-9_]+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)

    let tokens =
        normalizedSource
        .split(whereSeparator: { $0.isWhitespace })
        .map(String.init)
        .compactMap { token -> String? in
            let trimmed = token.trimmingCharacters(in: CharacterSet(charactersIn: "_"))
            return trimmed.isEmpty ? nil : trimmed.lowercased()
        }

    let baseName = tokens.enumerated().reduce(into: "") { partialResult, item in
        let (index, token) = item
        if index == 0 {
            partialResult += token
        } else {
            partialResult += token.prefix(1).uppercased() + token.dropFirst()
        }
    }

    guard let firstCharacter = baseName.first else {
        return fallbackPrefix
    }

    if firstCharacter.isLetter || firstCharacter == "_" {
        return baseName
    }

    return fallbackPrefix + String(firstCharacter).uppercased() + baseName.dropFirst()
}
