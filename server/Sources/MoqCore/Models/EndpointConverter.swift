import Foundation

/// Converts parsed spec endpoints into domain Endpoint models.
/// Standalone and testable without framework dependencies.
public enum EndpointConverter {
    public static func makeEndpoint(from parsed: ParsedEndpoint) -> Endpoint {
        let variants = parsed.responses.map { resp in
            ResponseVariant(
                name: resp.name,
                statusCode: HTTPStatusCode(code: UInt(resp.statusCode)),
                headers: resp.headers,
                body: resp.body,
                requestMatch: resp.requestMatch
            )
        }

        return Endpoint(
            key: EndpointKey(
                method: HTTPMethodValue(rawValue: parsed.method.uppercased()),
                path: parsed.path
            ),
            authRequirement: parsed.authRequirement,
            variants: variants,
            queryParamRules: parsed.requiredQueryParameters.map {
                RuleMatcher(name: $0, required: true, matchType: .require)
            },
            headerRules: parsed.requiredHeaders.map {
                RuleMatcher(name: $0, required: true, matchType: .require)
            },
            requiresBody: parsed.requiresBody,
            acceptedContentTypes: parsed.acceptedContentTypes
        )
    }

    public static func makeEndpoints(from spec: ParsedSpec) -> [Endpoint] {
        spec.endpoints.map(makeEndpoint(from:))
    }
}
