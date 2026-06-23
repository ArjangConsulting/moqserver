import Foundation
import MoqCore

/// Shared endpoint factory for MoqRuntimeTests.
/// Provides a flexible initializer that covers parameters used across test suites.
func makeTestEndpoint(
    method: HTTPMethodValue,
    path: String,
    auth: AuthRequirement = .none,
    variants: [ResponseVariant]? = nil,
    queryParamRules: [RuleMatcher] = [],
    headerRules: [RuleMatcher] = [],
    cookieRules: [RuleMatcher] = [],
    verifyCookies: Bool = false,
    requiresBody: Bool = false,
    acceptedContentTypes: [String] = [],
    network: NetworkBehavior? = nil
) -> Endpoint {
    let defaultVariants = [
        ResponseVariant(
            name: "default",
            statusCode: .ok,
            headers: [("Content-Type", "application/json")],
            body: Data(#"{"ok":true}"#.utf8)
        )
    ]
    return Endpoint(
        key: EndpointKey(method: method, path: path),
        authRequirement: auth,
        variants: variants ?? defaultVariants,
        queryParamRules: queryParamRules,
        headerRules: headerRules,
        cookieRules: cookieRules,
        verifyCookies: verifyCookies,
        requiresBody: requiresBody,
        acceptedContentTypes: acceptedContentTypes,
        network: network
    )
}
