import Foundation
import MoqCore

/// Converts .moqproj domain models to runtime Endpoint models for serving.
public enum ProjectToRuntimeConverter {

    /// Convert an entire MoqProject to runtime endpoints.
    public static func convert(_ project: MoqProject) throws -> [Endpoint] {
        try project.endpoints.map { doc in
            try convertEndpoint(doc, defaults: project.manifest.defaults, projectPath: project.projectPath)
        }
    }

    /// Convert a single EndpointDocument to a runtime Endpoint.
    public static func convertEndpoint(
        _ doc: EndpointDocument,
        defaults: ProjectDefaults,
        projectPath: String
    ) throws -> Endpoint {
        let method = HTTPMethodValue(rawValue: doc.method)
        let key = EndpointKey(method: method, path: doc.path)

        let auth = convertAuth(doc.auth ?? defaults.auth)

        let variants = try doc.variants.map { variant in
            try convertVariant(variant, defaults: defaults, projectPath: projectPath)
        }

        // Build required headers from request_rules
        var requiredHeaders: [String] = []
        if let rules = doc.requestRules?.headers {
            for matcher in rules where matcher.required == true {
                requiredHeaders.append(matcher.name)
            }
        }

        // Build required query parameters from request_rules
        var requiredQueryParams: [String] = []
        if let rules = doc.requestRules?.queryParams {
            for matcher in rules where matcher.required == true {
                requiredQueryParams.append(matcher.name)
            }
        }

        return Endpoint(
            key: key,
            authRequirement: auth,
            variants: variants,
            requiredQueryParameters: requiredQueryParams,
            requiredHeaders: requiredHeaders,
            operation: doc.operation
        )
    }

    // MARK: - Auth Conversion

    static func convertAuth(_ auth: ProjectAuthConfig) -> AuthRequirement {
        guard auth.verify else { return .none }

        switch auth.type {
        case .none:
            return .none
        case .bearer:
            return .bearer
        case .basic:
            return .basic
        case .apiKey:
            return .apiKey(headerName: auth.headerName ?? "X-API-Key")
        case .header:
            return .apiKey(headerName: auth.headerName ?? "X-Custom-Header")
        }
    }

    // MARK: - Variant Conversion

    static func convertVariant(
        _ variant: ProjectVariant,
        defaults: ProjectDefaults,
        projectPath: String
    ) throws -> ResponseVariant {
        let statusCode = HTTPStatusCode(code: UInt(variant.status))

        // Build headers
        var headers: [(String, String)] = []
        if let variantHeaders = variant.headers {
            for (key, value) in variantHeaders.sorted(by: { $0.key < $1.key }) {
                headers.append((key, value))
            }
        }
        if headers.isEmpty {
            headers = [("Content-Type", "application/json")]
        }

        // Resolve body
        let body: Data?
        if let bodyFile = variant.bodyFile {
            let fixturePath = (projectPath as NSString).appendingPathComponent(bodyFile)
            body = try Data(contentsOf: URL(fileURLWithPath: fixturePath))
        } else if let bodyValue = variant.body {
            body = bodyValue.toJSONData(prettyPrinted: false)
        } else {
            body = nil
        }

        // Calculate delay
        let variantDelay = variant.delayMs ?? 0
        let defaultDelay = defaults.delayMs
        let totalDelayMs = variantDelay + defaultDelay
        let delay: TimeInterval? = totalDelayMs > 0 ? TimeInterval(totalDelayMs) / 1000.0 : nil

        return ResponseVariant(
            name: variant.name,
            statusCode: statusCode,
            headers: headers,
            body: body,
            delay: delay
        )
    }
}
