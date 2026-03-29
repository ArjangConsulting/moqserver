import Foundation

/// Default implementation of request validation logic.
/// Unit-testable without any web framework dependency.
public struct RequestValidator: RequestValidating {
    public init() {}

    public func validate(endpoint: Endpoint, context: RequestContext) -> RequestValidationError? {
        if let error = validateRules(endpoint.queryParamRules, values: context.queryParameters, kind: .queryParam) {
            return error
        }

        if let error = validateRules(endpoint.headerRules, values: context.headers, kind: .header) {
            return error
        }

        let cookieValues = endpoint.verifyCookies ? mergedCookieValues(endpoint.cookieRules, cookies: context.cookies) : context.cookies
        if let error = validateRules(endpoint.cookieRules, values: cookieValues, kind: .cookie) {
            return error
        }
        if endpoint.verifyCookies,
           let error = validateCookiePresence(context.cookies, explicitRules: endpoint.cookieRules) {
            return error
        }

        if endpoint.requiresBody && !context.hasBody {
            return RequestValidationError(
                statusCode: .badRequest,
                code: .missingRequestBody,
                message: "Request body is required"
            )
        }

        if context.hasBody, !endpoint.acceptedContentTypes.isEmpty {
            let requestType = (context.contentType ?? "")
                .split(separator: ";")
                .first
                .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).lowercased() } ?? ""

            let supported = endpoint.acceptedContentTypes.map { $0.lowercased() }
            let matches = supported.contains { matchesContentType(request: requestType, expected: $0) }
            if !matches {
                return RequestValidationError(
                    statusCode: .unsupportedMediaType,
                    code: .unsupportedContentType,
                    message: "Unsupported Content-Type '\(requestType)'. Expected one of: \(supported.joined(separator: ", "))"
                )
            }
        }

        return nil
    }

    private func matchesContentType(request: String, expected: String) -> Bool {
        if expected == "*/*" { return true }
        if request == expected { return true }

        let requestParts = request.split(separator: "/", maxSplits: 1).map(String.init)
        let expectedParts = expected.split(separator: "/", maxSplits: 1).map(String.init)
        guard requestParts.count == 2, expectedParts.count == 2 else { return false }
        if expectedParts[1] == "*" {
            return requestParts[0] == expectedParts[0]
        }
        return false
    }

    private enum RuleKind {
        case header
        case queryParam
        case cookie

        var label: String {
            switch self {
            case .header:
                return "Header"
            case .queryParam:
                return "Query parameter"
            case .cookie:
                return "Cookie"
            }
        }

        var missingCode: RequestValidationErrorCode {
            switch self {
            case .header:
                return .missingRequiredHeader
            case .queryParam:
                return .missingRequiredQueryParam
            case .cookie:
                return .missingRequiredCookie
            }
        }

        var mismatchCode: RequestValidationErrorCode {
            switch self {
            case .header:
                return .headerValueMismatch
            case .queryParam:
                return .queryParamValueMismatch
            case .cookie:
                return .cookieValueMismatch
            }
        }
    }

    private func validateRules(
        _ rules: [RuleMatcher],
        values: [String: String],
        kind: RuleKind
    ) -> RequestValidationError? {
        for rule in rules {
            let actualValue = resolvedValue(for: rule.name, in: values, caseInsensitive: kind == .header)
            if let failure = RuleEvaluator.evaluate(rule, actualValue: actualValue) {
                switch failure {
                case .missing:
                    return RequestValidationError(
                        statusCode: .badRequest,
                        code: kind.missingCode,
                        message: "\(kind.label) '\(rule.name)' is required"
                    )
                case .mismatch(let expectedDescription, let actualValue):
                    let actualDescription = actualValue.map { "got '\($0)'" } ?? "but was missing"
                    return RequestValidationError(
                        statusCode: .badRequest,
                        code: kind.mismatchCode,
                        message: "\(kind.label) '\(rule.name)' must \(expectedDescription), \(actualDescription)"
                    )
                }
            }
        }
        return nil
    }

    private func validateCookiePresence(
        _ cookies: [String: String],
        explicitRules: [RuleMatcher]
    ) -> RequestValidationError? {
        let requiredCookieNames = Set(explicitRules.map(\.name))
        if !cookies.isEmpty || !requiredCookieNames.isEmpty {
            return nil
        }
        return RequestValidationError(
            statusCode: .badRequest,
            code: .missingRequiredCookie,
            message: "At least one cookie is required"
        )
    }

    private func mergedCookieValues(_ rules: [RuleMatcher], cookies: [String: String]) -> [String: String] {
        guard !rules.isEmpty else { return cookies }
        var merged = cookies
        for rule in rules where merged[rule.name] == nil {
            merged[rule.name] = nil
        }
        return merged
    }

    private func resolvedValue(for name: String, in values: [String: String], caseInsensitive: Bool) -> String? {
        if let exact = values[name] {
            return exact
        }
        guard caseInsensitive else { return nil }
        return values.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}
