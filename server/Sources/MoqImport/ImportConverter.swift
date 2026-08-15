import Foundation
import MoqCore

/// Which parsed endpoints participate in a conversion or merge, computed by the caller ahead of
/// time (e.g. from user-selected paths/tags, or "accept everything"). Filters are intersected:
/// an endpoint must match every supplied filter; an omitted filter accepts all.
public struct ImportSelection: Sendable {
    public let acceptedPaths: Set<String>?
    public let acceptedTags: Set<String>?

    public init(acceptedPaths: Set<String>? = nil, acceptedTags: Set<String>? = nil) {
        self.acceptedPaths = acceptedPaths
        self.acceptedTags = acceptedTags
    }

    public static let all = ImportSelection()

    func accepts(_ endpoint: ParsedEndpoint) -> Bool {
        if let acceptedPaths, !acceptedPaths.contains(endpoint.path) { return false }
        if let acceptedTags, Set(endpoint.tags).isDisjoint(with: acceptedTags) { return false }
        return true
    }
}

/// Governs how `ImportConverter.merge` treats endpoints that already exist in the target
/// project.
public struct ImportMergePolicy: Sendable {
    /// Update spec-owned fields (auth, request rules, tags) on endpoints that already exist.
    /// User-owned fields (alias, description, reference_name, defaults, delay, request_match,
    /// isDefault, variant names/reference names) are always preserved regardless of this flag.
    public let updateDetails: Bool
    /// Overwrite the body of an existing variant when the spec has a response at the same
    /// status code with a different body. When `false` (the default), existing variant bodies
    /// are never touched — only genuinely new status-code variants are added.
    public let replaceExistingBodies: Bool

    public init(updateDetails: Bool = true, replaceExistingBodies: Bool = false) {
        self.updateDetails = updateDetails
        self.replaceExistingBodies = replaceExistingBodies
    }
}

/// Converts a `ParsedSpec` into a `MoqProject`, or merges it into an existing one.
///
/// This intentionally simplifies Kotlin's `ImportConverter`
/// (`studio/studio-domain/src/commonMain/kotlin/com/moqserver/studio/domain/ImportConverter.kt`):
/// there is no interactive per-endpoint review step here (no `ImportEndpointEntry.accepted`, no
/// AI-generated response selection — that stays a Studio-only workflow). `ImportSelection`
/// replaces per-entry acceptance, and endpoint NEW/CHANGED/UNCHANGED classification happens
/// internally in `merge` by diffing against the target project, rather than being precomputed
/// for a review UI to display.
public enum ImportConverter {
    public static func convert(_ spec: ParsedSpec, selection: ImportSelection, name: String, path: String) -> MoqProject {
        var assignedReferenceNames: [String] = []
        let accepted = spec.endpoints.filter(selection.accepts)
        let endpoints = accepted.map { convertEndpoint($0, assignedReferenceNames: &assignedReferenceNames) }

        let manifest = ProjectManifest(
            version: MoqFormatRules.formatVersion,
            name: name,
            description: "Imported from \(spec.title) (v\(spec.version))",
            defaults: ProjectDefaults(
                delayMs: 0,
                auth: ProjectAuthConfig(type: .none, verify: false),
                network: NetworkBehavior()
            )
        )
        return MoqProject(manifest: manifest, endpoints: endpoints, projectPath: path)
    }

    /// Merges accepted import entries into an existing project.
    ///
    /// - New endpoints (no existing endpoint at the same id) are converted fresh and appended.
    /// - Existing endpoints never lose variants or fields merely because the spec omits or
    ///   changes them: spec-owned fields (auth, request rules, tags) are only updated when
    ///   `policy.updateDetails` is true, and existing variant bodies are only overwritten when
    ///   `policy.replaceExistingBodies` is true. New status-code variants from the spec are
    ///   always added.
    public static func merge(
        _ spec: ParsedSpec, selection: ImportSelection, policy: ImportMergePolicy, into existing: MoqProject
    ) -> MoqProject {
        let accepted = spec.endpoints.filter(selection.accepts)
        var existingByID: [String: EndpointDocument] = [:]
        for endpoint in existing.endpoints { existingByID[endpoint.id] = endpoint }

        var assignedReferenceNames = existing.endpoints.map(\.referenceName)
        var mergedEndpoints = existing.endpoints
        var newEndpoints: [EndpointDocument] = []

        for parsed in accepted {
            let id = endpointID(method: parsed.method, path: parsed.path)
            if let index = mergedEndpoints.firstIndex(where: { $0.id == id }) {
                mergedEndpoints[index] = mergeEndpoint(mergedEndpoints[index], parsed: parsed, policy: policy)
            } else {
                newEndpoints.append(convertEndpoint(parsed, assignedReferenceNames: &assignedReferenceNames))
            }
        }

        return MoqProject(
            manifest: existing.manifest, endpoints: mergedEndpoints + newEndpoints, projectPath: existing.projectPath)
    }

    /// Compares a freshly parsed endpoint against an existing one, reporting only spec-owned,
    /// non-user-editable differences.
    public static func diff(_ parsed: ParsedEndpoint, existing: EndpointDocument) -> EndpointSpecDiff {
        let parsedStatusCodes = Set(parsed.responses.map(\.statusCode))
        let existingStatusCodes = Set(existing.variants.map(\.status))
        let newStatusCodes = parsedStatusCodes.subtracting(existingStatusCodes)
        let removedStatusCodes = existingStatusCodes.subtracting(parsedStatusCodes)

        let parsedAuth: (ProjectAuthConfig.AuthType, String?)? =
            parsed.authType != .none ? (parsed.authType, parsed.authHeaderName) : nil
        let existingAuth: (ProjectAuthConfig.AuthType, String?)? = existing.auth.map { ($0.type, $0.headerName) }
        let authChanged = !authPairsEqual(parsedAuth, existingAuth)

        let parsedRequiredHeaders = Set(parsed.requiredHeaders)
        let existingRequiredHeaders = Set(
            (existing.requestRules?.headers ?? []).filter { $0.required == true }.map(\.name))

        let parsedQueryParams =
            !parsed.queryParameters.isEmpty
            ? Set(parsed.queryParameters.map(\.name)) : Set(parsed.requiredQueryParameters)
        let existingQueryParams = Set((existing.requestRules?.queryParams ?? []).map(\.name))

        let parsedCookies = Set(parsed.cookies.map(\.name))
        let existingCookies = Set((existing.requestRules?.cookies ?? []).map(\.name))

        let requestRulesChanged =
            parsedRequiredHeaders != existingRequiredHeaders || parsedQueryParams != existingQueryParams
            || parsedCookies != existingCookies

        let tagsChanged = Set(parsed.tags) != Set(existing.tags ?? [])

        return EndpointSpecDiff(
            newStatusCodes: newStatusCodes,
            removedStatusCodes: removedStatusCodes,
            authChanged: authChanged,
            requestRulesChanged: requestRulesChanged,
            tagsChanged: tagsChanged
        )
    }

    private static func authPairsEqual(
        _ lhs: (ProjectAuthConfig.AuthType, String?)?, _ rhs: (ProjectAuthConfig.AuthType, String?)?
    ) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case (let l?, let r?): return l.0 == r.0 && l.1 == r.1
        default: return false
        }
    }

    // MARK: - Merge

    private static func mergeEndpoint(
        _ existing: EndpointDocument, parsed: ParsedEndpoint, policy: ImportMergePolicy
    ) -> EndpointDocument {
        let diff = diff(parsed, existing: existing)

        let auth: ProjectAuthConfig? = (policy.updateDetails && diff.authChanged) ? parsed.toProjectAuthConfig() : existing.auth
        let requestRules: RequestRules? =
            (policy.updateDetails && diff.requestRulesChanged) ? buildRequestRules(parsed) : existing.requestRules
        let tags: [String]? =
            (policy.updateDetails && diff.tagsChanged) ? (parsed.tags.isEmpty ? nil : parsed.tags) : existing.tags

        return EndpointDocument(
            id: existing.id,
            alias: existing.alias,
            description: existing.description,
            referenceName: existing.referenceName,
            method: existing.method,
            path: existing.path,
            tags: tags,
            auth: auth,
            requestRules: requestRules,
            operation: existing.operation,
            network: existing.network,
            variants: mergeVariants(existing, parsed: parsed, policy: policy)
        )
    }

    /// Existing variants are always kept, and a status code already covered by an existing
    /// variant never spawns a duplicate "new" variant for that status — `replaceExistingBodies`
    /// only controls whether that existing variant's content gets overwritten in place. Status
    /// codes with no existing variant are always appended as new variants.
    private static func mergeVariants(
        _ existing: EndpointDocument, parsed: ParsedEndpoint, policy: ImportMergePolicy
    ) -> [ProjectVariant] {
        let existingStatusCodes = Set(existing.variants.map(\.status))

        var responsesByStatus: [Int: [ParsedResponse]] = [:]
        var statusOrder: [Int] = []
        for response in parsed.responses {
            if responsesByStatus[response.statusCode] == nil { statusOrder.append(response.statusCode) }
            responsesByStatus[response.statusCode, default: []].append(response)
        }

        var updatedExisting = existing.variants
        if policy.replaceExistingBodies {
            updatedExisting = existing.variants.map { variant -> ProjectVariant in
                guard var candidates = responsesByStatus[variant.status], !candidates.isEmpty else { return variant }
                let replacementIndex = candidates.firstIndex { $0.name == variant.name } ?? (candidates.count == 1 ? 0 : nil)
                guard let replacementIndex else { return variant }
                let replacement = candidates.remove(at: replacementIndex)
                responsesByStatus[variant.status] = candidates
                let replacementBody = replacement.body.map(parseBody)
                return ProjectVariant(
                    name: variant.name,
                    referenceName: variant.referenceName,
                    description: replacement.description ?? variant.description,
                    isDefault: variant.isDefault,
                    status: variant.status,
                    headers: replacement.headers.isEmpty ? variant.headers : replacement.headers,
                    requestMatch: variant.requestMatch,
                    body: replacementBody,
                    bodyFile: replacementBody != nil ? nil : variant.bodyFile,
                    delayMs: variant.delayMs
                )
            }
        }

        var assignedNames = existing.variants.map(\.name)
        var assignedReferenceNames = existing.variants.map(\.referenceName)
        let newResponses = statusOrder.filter { !existingStatusCodes.contains($0) }.flatMap { responsesByStatus[$0] ?? [] }
        let newVariants = newResponses.map { response -> ProjectVariant in
            let name = suggestedVariantName(status: response.statusCode, existingNames: assignedNames, preferredName: response.name)
            assignedNames.append(name)
            let referenceName = suggestedVariantReferenceName(
                preferredSource: name, status: response.statusCode, existingNames: assignedReferenceNames)
            assignedReferenceNames.append(referenceName)
            return convertVariant(response, name: name, referenceName: referenceName, isDefault: false)
        }

        return updatedExisting + newVariants
    }

    // MARK: - Fresh conversion

    private static func convertEndpoint(_ parsed: ParsedEndpoint, assignedReferenceNames: inout [String]) -> EndpointDocument {
        let id = endpointID(method: parsed.method, path: parsed.path)
        let alias = parsed.alias?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            ?? EndpointAlias.defaultAlias(method: parsed.method, path: parsed.path)
        let referenceName = suggestedEndpointReferenceName(
            preferredSource: parsed.referenceName ?? alias, fallbackID: id, existingNames: assignedReferenceNames)
        assignedReferenceNames.append(referenceName)

        var variantNames: [String] = []
        var variantReferenceNames: [String] = []
        let defaultIndex = defaultVariantIndex(parsed.responses)
        let variants = parsed.responses.enumerated().map { index, response -> ProjectVariant in
            let name = suggestedVariantName(status: response.statusCode, existingNames: variantNames, preferredName: response.name)
            variantNames.append(name)
            let refName = suggestedVariantReferenceName(
                preferredSource: name, status: response.statusCode, existingNames: variantReferenceNames)
            variantReferenceNames.append(refName)
            return convertVariant(response, name: name, referenceName: refName, isDefault: index == defaultIndex)
        }

        return EndpointDocument(
            id: id,
            alias: alias,
            description: parsed.description?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            referenceName: referenceName,
            method: parsed.method.uppercased(),
            path: parsed.path,
            tags: parsed.tags.isEmpty ? nil : parsed.tags,
            auth: parsed.toProjectAuthConfig(),
            requestRules: buildRequestRules(parsed),
            variants: variants
        )
    }

    private static func defaultVariantIndex(_ responses: [ParsedResponse]) -> Int {
        guard !responses.isEmpty else { return -1 }
        if let index = responses.firstIndex(where: { $0.name == "default" }) { return index }
        if let index = responses.firstIndex(where: { (200...299).contains($0.statusCode) }) { return index }
        return 0
    }

    private static func convertVariant(
        _ response: ParsedResponse, name: String, referenceName: String, isDefault: Bool
    ) -> ProjectVariant {
        ProjectVariant(
            name: name,
            referenceName: referenceName,
            description: response.description,
            isDefault: isDefault ? true : nil,
            status: response.statusCode,
            headers: response.headers.isEmpty ? nil : response.headers,
            body: response.body.map(parseBody)
        )
    }

    private static func buildRequestRules(_ parsed: ParsedEndpoint) -> RequestRules? {
        let headers = parsed.requiredHeaders.map { RuleMatcher(name: $0, required: true) }
        let queryParams =
            !parsed.queryParameters.isEmpty
            ? parsed.queryParameters : parsed.requiredQueryParameters.map { RuleMatcher(name: $0, required: true) }
        let cookies = parsed.cookies

        guard !headers.isEmpty || !queryParams.isEmpty || !cookies.isEmpty else { return nil }
        return RequestRules(
            headers: headers.isEmpty ? nil : headers,
            queryParams: queryParams.isEmpty ? nil : queryParams,
            cookies: cookies.isEmpty ? nil : cookies
        )
    }

    /// Generates a deterministic endpoint id from method + path.
    public static func endpointID(method: String, path: String) -> String {
        var normalized = path
        if normalized.hasPrefix("/") { normalized.removeFirst() }
        normalized = normalized.replacingOccurrences(of: #"\{[^}]+\}"#, with: "param", options: .regularExpression)
        normalized = normalized.replacingOccurrences(of: "[^a-zA-Z0-9/]", with: "", options: .regularExpression)
        normalized = normalized.replacingOccurrences(of: "/", with: "-")
        normalized = normalized.lowercased()
        while normalized.hasSuffix("-") { normalized.removeLast() }

        let id = "\(method.lowercased())-\(normalized)"
        return normalized.isEmpty ? "\(method.lowercased())-root" : id
    }

    /// Parses a raw response body string into a structured value: JSON text becomes a decoded
    /// `AnyCodableValue` tree; anything that fails to parse as JSON (including plain text) is
    /// kept as a `.string`.
    private static func parseBody(_ raw: String) -> AnyCodableValue {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .string(raw) }
        guard let data = trimmed.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else {
            return .string(raw)
        }
        return jsonObjectToAnyCodableValue(object)
    }

    private static func jsonObjectToAnyCodableValue(_ object: Any) -> AnyCodableValue {
        switch object {
        case is NSNull:
            return .null
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .bool(number.boolValue)
            }
            if number.stringValue.contains(".") || number.stringValue.lowercased().contains("e") {
                return .double(number.doubleValue)
            }
            return .int(number.intValue)
        case let string as String:
            return .string(string)
        case let array as [Any]:
            return .array(array.map(jsonObjectToAnyCodableValue))
        case let dict as [String: Any]:
            return .object(dict.mapValues(jsonObjectToAnyCodableValue))
        default:
            return .null
        }
    }
}

extension ParsedEndpoint {
    fileprivate func toProjectAuthConfig() -> ProjectAuthConfig? {
        guard authType != .none else { return nil }
        return ProjectAuthConfig(type: authType, verify: true, headerName: authHeaderName)
    }
}

extension String {
    fileprivate var nonEmpty: String? { isEmpty ? nil : self }
}

/// Describes which spec-owned changes were detected between a freshly parsed endpoint and an
/// existing project endpoint.
public struct EndpointSpecDiff: Sendable, Equatable {
    public let newStatusCodes: Set<Int>
    public let removedStatusCodes: Set<Int>
    public let authChanged: Bool
    public let requestRulesChanged: Bool
    public let tagsChanged: Bool

    public init(
        newStatusCodes: Set<Int> = [], removedStatusCodes: Set<Int> = [], authChanged: Bool = false,
        requestRulesChanged: Bool = false, tagsChanged: Bool = false
    ) {
        self.newStatusCodes = newStatusCodes
        self.removedStatusCodes = removedStatusCodes
        self.authChanged = authChanged
        self.requestRulesChanged = requestRulesChanged
        self.tagsChanged = tagsChanged
    }

    public var hasChanges: Bool {
        !newStatusCodes.isEmpty || !removedStatusCodes.isEmpty || authChanged || requestRulesChanged || tagsChanged
    }
}
