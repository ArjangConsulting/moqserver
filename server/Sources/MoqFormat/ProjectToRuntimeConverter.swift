import Foundation
import MoqCore

/// Converts .moqproj domain models to runtime Endpoint models for serving.
public enum ProjectToRuntimeConverter {

    /// Convert an entire MoqProject to runtime endpoints.
    public static func convert(_ project: MoqProject) throws -> [Endpoint] {
        try project.endpoints.map { doc in
            try convertEndpoint(
                doc,
                defaults: project.manifest.defaults,
                globalRules: project.manifest.globalRules,
                projectPath: project.projectPath
            )
        }
    }

    /// Convert a single EndpointDocument to a runtime Endpoint.
    public static func convertEndpoint(
        _ doc: EndpointDocument,
        defaults: ProjectDefaults,
        globalRules: GlobalRules? = nil,
        projectPath: String
    ) throws -> Endpoint {
        let method = HTTPMethodValue(rawValue: doc.method)
        let key = EndpointKey(method: method, path: doc.path)

        let auth = convertAuth(doc.auth ?? defaults.auth)

        let variants = try doc.variants.map { variant in
            try convertVariant(variant, defaults: defaults, projectPath: projectPath)
        }

        let headerRules = mergeRules(globalRules?.requiredHeaders, doc.requestRules?.headers)
        let queryParamRules = doc.requestRules?.queryParams ?? []
        let cookieRules = doc.requestRules?.cookies ?? []
        let network = mergeNetwork(defaults: defaults.network, override: doc.network)
        let verifyCookies = doc.requestRules?.verifyCookies ?? globalRules?.verifyCookies ?? false

        return Endpoint(
            key: key,
            authRequirement: auth,
            variants: variants,
            queryParamRules: queryParamRules,
            headerRules: headerRules,
            cookieRules: cookieRules,
            verifyCookies: verifyCookies,
            operation: doc.operation,
            network: network
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
            referenceName: variant.referenceName,
            isDefault: variant.isDefault == true,
            statusCode: statusCode,
            headers: headers,
            body: body,
            delay: delay,
            requestMatch: variant.requestMatch
        )
    }

    private static func mergeRules(_ globalRules: [RuleMatcher]?, _ endpointRules: [RuleMatcher]?) -> [RuleMatcher] {
        var merged: [RuleMatcher] = []
        for rule in globalRules ?? [] {
            merged.append(rule)
        }
        for rule in endpointRules ?? [] where !merged.contains(where: { $0.name == rule.name }) {
            merged.append(rule)
        }
        for rule in endpointRules ?? [] where merged.contains(where: { $0.name == rule.name }) {
            if let index = merged.firstIndex(where: { $0.name == rule.name }) {
                merged[index] = rule
            }
        }
        return merged
    }

    private static func mergeNetwork(defaults: NetworkBehavior, override: NetworkBehavior?) -> NetworkBehavior {
        NetworkBehavior(
            latencyMs: override?.latencyMs ?? defaults.latencyMs,
            jitterMs: override?.jitterMs ?? defaults.jitterMs,
            packetLossPercent: override?.packetLossPercent ?? defaults.packetLossPercent
        )
    }
}
