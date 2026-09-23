import MoqAddonKit

extension AddonCatalog {
    /// The add-ons compiled into moqserver. Validation (`moqserver validate`, `moq-mcp`,
    /// `moq-format`, `moq-author`) and `serve` all read this, so a bundle is judged the same way
    /// by every entry point.
    public static let builtIn = AddonCatalog([
        JWTClaimsAddon.self
    ])
}
