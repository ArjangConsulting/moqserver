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
