import Foundation

/// Default implementation of request validation logic.
/// Unit-testable without any web framework dependency.
public struct RequestValidator: RequestValidating {
    public init() {}

    public func validate(endpoint: Endpoint, context: RequestContext) -> RequestValidationError? {
        let missingQuery = endpoint.requiredQueryParameters.filter { context.queryParameters[$0] == nil }
        if !missingQuery.isEmpty {
            return RequestValidationError(
                statusCode: .badRequest,
                message: "Missing required query parameter(s): \(missingQuery.joined(separator: ", "))"
            )
        }

        let missingHeaders = endpoint.requiredHeaders.filter {
            guard let value = context.headers[$0.lowercased()] ?? context.headers[$0] else { return true }
            return value.isEmpty
        }
        if !missingHeaders.isEmpty {
            return RequestValidationError(
                statusCode: .badRequest,
                message: "Missing required header(s): \(missingHeaders.joined(separator: ", "))"
            )
        }

        if endpoint.requiresBody && !context.hasBody {
            return RequestValidationError(
                statusCode: .badRequest,
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
}
