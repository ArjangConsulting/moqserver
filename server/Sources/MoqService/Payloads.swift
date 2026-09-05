import MoqCore
import MoqFormat
import MoqImport

// MARK: - Project lifecycle

public struct ProjectDescription: Codable, Sendable {
    public let name: String
    public let description: String?
    public let path: String
    public let endpointCount: Int
    public let dirty: Bool
    public let revision: String?

    enum CodingKeys: String, CodingKey {
        case name, description, path
        case endpointCount = "endpoint_count"
        case dirty, revision
    }

    public init(
        name: String, description: String?, path: String, endpointCount: Int, dirty: Bool, revision: String? = nil
    ) {
        self.name = name
        self.description = description
        self.path = path
        self.endpointCount = endpointCount
        self.dirty = dirty
        self.revision = revision
    }
}

// MARK: - Endpoint listing / reading

public struct EndpointFilter: Decodable, Sendable {
    public let filterPath: String?
    public let filterMethod: String?
    public let filterTag: String?

    enum CodingKeys: String, CodingKey {
        case filterPath = "filter_path"
        case filterMethod = "filter_method"
        case filterTag = "filter_tag"
    }

    public init(filterPath: String? = nil, filterMethod: String? = nil, filterTag: String? = nil) {
        self.filterPath = filterPath
        self.filterMethod = filterMethod
        self.filterTag = filterTag
    }
}

public struct EndpointListResult: Codable, Sendable {
    public let endpoints: [EndpointSummary]

    public init(endpoints: [EndpointSummary]) {
        self.endpoints = endpoints
    }
}

public struct EndpointSummary: Codable, Sendable {
    public let id: String
    public let method: String
    public let path: String
    public let alias: String?
    public let referenceName: String
    public let variantCount: Int
    public let defaultVariant: String?

    enum CodingKeys: String, CodingKey {
        case id, method, path, alias
        case referenceName = "reference_name"
        case variantCount = "variant_count"
        case defaultVariant = "default_variant"
    }

    public init(
        id: String, method: String, path: String, alias: String?, referenceName: String, variantCount: Int,
        defaultVariant: String?
    ) {
        self.id = id
        self.method = method
        self.path = path
        self.alias = alias
        self.referenceName = referenceName
        self.variantCount = variantCount
        self.defaultVariant = defaultVariant
    }
}

// MARK: - Endpoint naming helper (stateless)

public struct SuggestEndpointIDInput: Decodable, Sendable {
    public let method: String
    public let path: String
    public let alias: String?

    public init(method: String, path: String, alias: String?) {
        self.method = method
        self.path = path
        self.alias = alias
    }
}

public struct SuggestedEndpointIdentity: Codable, Sendable {
    public let id: String
    public let alias: String
    public let referenceName: String

    enum CodingKeys: String, CodingKey {
        case id, alias
        case referenceName = "reference_name"
    }

    public init(id: String, alias: String, referenceName: String) {
        self.id = id
        self.alias = alias
        self.referenceName = referenceName
    }
}

// MARK: - Endpoint mutation

/// Input shape for upserting an endpoint's metadata. Deliberately separate from
/// `EndpointDocument` (rather than decoding into it directly) because `EndpointDocument.variants`
/// is non-optional — this call authors/updates endpoint metadata only; variants are managed by
/// `upsertVariant` / `removeVariant` and preserved across a metadata update.
public struct EndpointUpsertInput: Decodable, Sendable {
    public let id: String
    public let alias: String?
    public let description: String?
    public let referenceName: String?
    public let method: String
    public let path: String
    public let tags: [String]?
    public let auth: ProjectAuthConfig?
    public let requestRules: RequestRules?
    public let operation: EndpointOperation?
    public let network: NetworkBehavior?
    public let strictCallCount: Bool?

    enum CodingKeys: String, CodingKey {
        case id, alias, description
        case referenceName = "reference_name"
        case method, path, tags, auth
        case requestRules = "request_rules"
        case operation, network
        case strictCallCount = "strict_call_count"
    }

    /// Builds the full `EndpointDocument`, preserving `existingVariants` (`[]` for a new
    /// endpoint) since this call never touches variants.
    public func makeDocument(existingVariants: [ProjectVariant]) -> EndpointDocument {
        EndpointDocument(
            id: id,
            alias: alias,
            description: description,
            referenceName: referenceName,
            method: method,
            path: path,
            tags: tags,
            auth: auth,
            requestRules: requestRules,
            operation: operation,
            network: network,
            variants: existingVariants,
            strictCallCount: strictCallCount
        )
    }
}

public struct RemoveVariantInput: Decodable, Sendable {
    public let endpointId: String
    public let name: String

    enum CodingKeys: String, CodingKey {
        case endpointId = "endpoint_id"
        case name
    }

    public init(endpointId: String, name: String) {
        self.endpointId = endpointId
        self.name = name
    }
}

// MARK: - Validation

public struct DiagnosticPayload: Codable, Sendable {
    public let severity: String
    public let code: String?
    public let message: String
    public let file: String?
    public let field: String?
    public let endpointId: String?
    public let variantName: String?

    enum CodingKeys: String, CodingKey {
        case severity, code, message, file, field
        case endpointId = "endpoint_id"
        case variantName = "variant_name"
    }

    public init(_ diagnostic: ValidationDiagnostic) {
        severity = diagnostic.severity.rawValue
        code = diagnostic.code?.rawValue
        message = diagnostic.message
        file = diagnostic.file
        field = diagnostic.field
        endpointId = diagnostic.endpointID
        variantName = diagnostic.variantName
    }
}

public struct ValidationResult: Codable, Sendable {
    public let errorCount: Int
    public let warningCount: Int
    public let diagnostics: [DiagnosticPayload]

    enum CodingKeys: String, CodingKey {
        case errorCount = "error_count"
        case warningCount = "warning_count"
        case diagnostics
    }

    public init(diagnostics: [ValidationDiagnostic]) {
        let errors = diagnostics.filter { $0.severity == .error }
        self.errorCount = errors.count
        self.warningCount = diagnostics.count - errors.count
        self.diagnostics = diagnostics.map(DiagnosticPayload.init)
    }
}

// MARK: - Import

public struct ImportInputCommon: Decodable, Sendable {
    public let acceptPaths: [String]?
    public let updateDetails: Bool?
    public let replaceExistingBodies: Bool?

    enum CodingKeys: String, CodingKey {
        case acceptPaths = "accept_paths"
        case updateDetails = "update_details"
        case replaceExistingBodies = "replace_existing_bodies"
    }

    public init(acceptPaths: [String]? = nil, updateDetails: Bool? = nil, replaceExistingBodies: Bool? = nil) {
        self.acceptPaths = acceptPaths
        self.updateDetails = updateDetails
        self.replaceExistingBodies = replaceExistingBodies
    }
}

public struct ImportSummary: Codable, Sendable {
    public let projectEndpointCount: Int
    public let newEndpointCount: Int
    public let warnings: [String]

    enum CodingKeys: String, CodingKey {
        case projectEndpointCount = "project_endpoint_count"
        case newEndpointCount = "new_endpoint_count"
        case warnings
    }

    public init(projectEndpointCount: Int, newEndpointCount: Int, warnings: [String]) {
        self.projectEndpointCount = projectEndpointCount
        self.newEndpointCount = newEndpointCount
        self.warnings = warnings
    }
}

public struct ImportHARInput: Decodable, Sendable {
    public let path: String
    public init(path: String) { self.path = path }
}

public struct ImportOpenAPIInput: Decodable, Sendable {
    public let source: String
    public let auth: ImportAuthInput?

    public init(source: String, auth: ImportAuthInput? = nil) {
        self.source = source
        self.auth = auth
    }
}

public struct ImportAuthInput: Decodable, Sendable {
    public let bearer: String?
    public let basic: BasicAuthInput?
    public let header: HeaderAuthInput?

    public struct BasicAuthInput: Decodable, Sendable {
        public let username: String
        public let password: String
    }
    public struct HeaderAuthInput: Decodable, Sendable {
        public let name: String
        public let value: String
    }

    public init(bearer: String? = nil, basic: BasicAuthInput? = nil, header: HeaderAuthInput? = nil) {
        self.bearer = bearer
        self.basic = basic
        self.header = header
    }

    public var resolved: URLImportAuth? {
        if let bearer { return .bearer(bearer) }
        if let basic { return .basic(username: basic.username, password: basic.password) }
        if let header { return .header(name: header.name, value: header.value) }
        return nil
    }
}
