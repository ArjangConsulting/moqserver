import Foundation

public enum EndpointAlias {
    public static func defaultAlias(
        method: String,
        path: String,
        operation: EndpointOperation? = nil
    ) -> String {
        if let name = normalized(alias: operation?.name) {
            return humanize(name)
        }

        if path.caseInsensitiveCompare("/graphql") == .orderedSame {
            let operationLabel = operation.map { String(describing: $0.type).capitalized } ?? "GraphQL"
            return "\(operationLabel) Operation"
        }

        let verb = defaultVerb(method: method, path: path)
        let segments =
            path
            .split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
            .first?
            .split(separator: "/")
            .map(String.init)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { !isIgnoredPathSegment($0) } ?? []

        let resourceTokens =
            segments
            .filter { !isPathParameter($0) }
            .flatMap(tokenize)
        let parameterTokens =
            segments
            .filter(isPathParameter)
            .flatMap { segment in
                tokenize(String(segment.dropFirst().dropLast()))
            }

        let resourceLabel = (resourceTokens.isEmpty ? ["Root"] : resourceTokens).joined(separator: " ")
        let parameterLabel = parameterTokens.isEmpty ? "" : " By " + parameterTokens.joined(separator: " ")

        return verb + " " + resourceLabel + parameterLabel
    }

    public static func humanize(_ source: String) -> String {
        tokenize(source).joined(separator: " ")
    }

    static func normalized(alias: String?) -> String? {
        guard let alias = alias?.trimmingCharacters(in: .whitespacesAndNewlines), !alias.isEmpty else {
            return nil
        }
        return alias
    }

    private static func defaultVerb(method: String, path: String) -> String {
        switch method.uppercased() {
        case "GET":
            return path.contains("{") ? "Get" : "List"
        case "POST":
            return "Create"
        case "PUT", "PATCH":
            return "Update"
        case "DELETE":
            return "Delete"
        case "HEAD":
            return "Head"
        case "OPTIONS":
            return "Options"
        default:
            return method.uppercased()
        }
    }

    private static func tokenize(_ source: String) -> [String] {
        let spaced = source.replacingOccurrences(
            of: #"([a-z0-9])([A-Z])"#,
            with: "$1 $2",
            options: .regularExpression
        )
        let sanitized = spaced.replacingOccurrences(
            of: #"[^A-Za-z0-9]+"#,
            with: " ",
            options: .regularExpression
        )

        return
            sanitized
            .split(whereSeparator: \.isWhitespace)
            .map(String.init)
            .map { token in
                if token.count > 1 && token.allSatisfy(\.isUppercase) {
                    return token
                }
                return token.lowercased().capitalized
            }
    }

    private static func isIgnoredPathSegment(_ segment: String) -> Bool {
        let lowercased = segment.lowercased()
        return lowercased == "api"
            || lowercased.range(of: #"^v\d+$"#, options: .regularExpression) != nil
    }

    private static func isPathParameter(_ segment: String) -> Bool {
        segment.hasPrefix("{") && segment.hasSuffix("}")
    }
}
