import MoqCore

/// Input shape for `moq_upsert_endpoint`. Deliberately separate from `EndpointDocument` (rather
/// than decoding into it directly) because `EndpointDocument.variants` is non-optional — a tool
/// call authors/updates endpoint metadata only; variants are managed by `moq_upsert_variant` /
/// `moq_remove_variant` and preserved across an endpoint metadata update.
struct EndpointUpsertInput: Decodable {
    let id: String
    let alias: String?
    let description: String?
    let referenceName: String?
    let method: String
    let path: String
    let tags: [String]?
    let auth: ProjectAuthConfig?
    let requestRules: RequestRules?
    let operation: EndpointOperation?
    let network: NetworkBehavior?

    enum CodingKeys: String, CodingKey {
        case id, alias, description
        case referenceName = "reference_name"
        case method, path, tags, auth
        case requestRules = "request_rules"
        case operation, network
    }

    /// Builds the full `EndpointDocument`, preserving `existingVariants` (`[]` for a new
    /// endpoint) since this tool never touches variants.
    func makeDocument(existingVariants: [ProjectVariant]) -> EndpointDocument {
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
            variants: existingVariants
        )
    }
}
