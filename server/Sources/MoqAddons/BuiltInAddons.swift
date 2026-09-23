import MoqAddonKit

extension AddonCatalog {
    /// The add-ons a bundle can enable under `addons:`. Validation (`moqserver validate`,
    /// `moq-mcp`, `moq-format`, `moq-author`) and `serve` all read this, so a bundle is judged the
    /// same way by every entry point. `oauth-mock` is deliberately absent: it is always active and
    /// configured by the server config (see `buildApp`), not by bundles.
    public static let builtIn = AddonCatalog([
        JWTClaimsAddon.self
    ])
}
