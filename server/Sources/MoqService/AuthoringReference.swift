import Foundation
import MoqCore

/// The format contract an author needs before writing a bundle: the JSON Schema and the rules the
/// schema cannot express. Shared by every authoring entry point so `moq-author schema`/`rules` and
/// the `moq-mcp` resources can never disagree.
public enum AuthoringReference {
    /// `format/schema.json`, compiled into the binary so no resource bundle has to ship beside it.
    public static var schemaJSON: String {
        String(decoding: PackageResources.schema_json, as: UTF8.self)
    }

    /// Generated from live `MoqFormatRules`/`MatchType`/`ProjectAuthConfig.AuthType` values rather
    /// than hand-copied, so it can't silently drift from what the validator actually enforces.
    public static func rulesMarkdown() -> String {
        let methods = MoqFormatRules.supportedMethods.sorted().joined(separator: ", ")
        let matchTypes = MatchType.allCases.map(\.rawValue).sorted().joined(separator: ", ")
        let authTypes = ProjectAuthConfig.AuthType.allCases.map(\.rawValue).sorted().joined(separator: ", ")

        return """
            # moqproj authoring rules

            - Format version: `\(MoqFormatRules.formatVersion)`
            - Endpoint id pattern: `\(MoqFormatRules.endpointIDPattern)` (lowercase alphanumeric, hyphens, must not start with a hyphen)
            - Supported HTTP methods: \(methods)
            - Reserved paths (rejected): `/health`, `/_admin`, `/_admin/*`, `/_auth`, `/_auth/*`
            - At most one variant per endpoint may have `default: true`; a variant upsert clears siblings automatically. An endpoint with variants but no `default: true` gets a `W_NO_DEFAULT_VARIANT` warning — selection then falls back to declaration order
            - Variant names are unique **case-insensitively** (`Success` and `success` collide). Upserting a variant under an existing name — in any casing — replaces that variant in place and the result says `replaced`; do not follow up by removing the old spelling
            - A mutation fails with `E_PROJECT_CHANGED` if the bundle was edited on disk (by hand, or by another process) after it was opened. `moq-author` reopens the bundle on every command, so it only sees this when two commands race; a long-lived `moq-mcp` session must reopen the project rather than retry the same edit
            - A variant's `body` and `body_file` are mutually exclusive — a variant upsert only accepts inline `body` and manages fixture files itself
            - `request_match` (on a variant) must define at least one of `query`, `headers`, or `body_contains` when present
            - GraphQL endpoints use `path: /graphql` and require `operation.type` plus `operation.name` and/or `operation.document`; multiple endpoints may share `/graphql`, disambiguated by `operation`
            - `auth.type` values: \(authTypes) — `header_name` is required for `api-key` and `header`
            - `request_rules`/`variant.request_match` matcher `match_type` values: \(matchTypes)
            - Variant `status` must be 100-599
            - Variant `call_count` (integer, >= 1 when present) makes that variant eligible only on the Nth call to its endpoint; call_count values must be unique per endpoint
            - Endpoint `strict_call_count: true` (requires at least one variant with `call_count` set) rejects a call with no exact `call_count` match (409 `call_count_exceeded`) instead of falling back to normal selection; default `false`
            """
    }
}
