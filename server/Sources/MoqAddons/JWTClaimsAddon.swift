import Foundation
import Logging
import MoqAddonKit
import MoqCore

private let logger = Logger(label: "moqserver.addons.JWTClaimsAddon")

/// `jwt-claims`: decodes the bearer token on each request and exposes its claims, so a variant can
/// be picked by claim (`request_match.addons.jwt-claims.claims`) and request history records who
/// made each call.
///
/// ```yaml
/// # project.yml
/// addons:
///   jwt-claims:
///     verify_signature: false   # required; unsigned tokens (e.g. Firebase Auth emulator)
///     header: Authorization     # default
///     scheme: Bearer            # default; "" reads the raw header value
///     trace_claims: [sub]       # default; claims recorded in request history
/// ```
///
/// Signature verification is not implemented yet, so `verify_signature: false` must be set
/// explicitly, and serving is limited to a loopback bind unless `--allow-unverified-jwt` is passed:
/// anyone who can reach the server can forge a token with any claims.
public struct JWTClaimsAddon: MoqAddon {
    public static let id = "jwt-claims"

    struct Config: Equatable {
        var header = "Authorization"
        var scheme = "Bearer"
        var traceClaims = ["sub"]
    }

    let config: Config

    // MARK: - H8

    public static func validateConfig(_ config: AnyCodableValue) -> [AddonDiagnostic] {
        switch parseConfig(config) {
        case .success: return []
        case .failure(let diagnostics): return diagnostics.all
        }
    }

    public static func validateMatch(_ spec: AnyCodableValue) -> [AddonDiagnostic] {
        guard case .object(let fields) = spec else {
            return [AddonDiagnostic(message: "Must be a mapping with a `claims` key.")]
        }
        var diagnostics: [AddonDiagnostic] = []
        for key in fields.keys.sorted() where key != "claims" {
            diagnostics.append(AddonDiagnostic(field: key, message: "Unknown key. Supported: claims."))
        }
        guard case .object(let claims)? = fields["claims"], !claims.isEmpty else {
            diagnostics.append(
                AddonDiagnostic(field: "claims", message: "Must be a non-empty mapping of claim name to expected value."))
            return diagnostics
        }
        return diagnostics
    }

    // MARK: - Activation

    public init(config: AnyCodableValue, environment: AddonEnvironment) throws {
        switch Self.parseConfig(config) {
        case .success(let parsed):
            self.config = parsed
        case .failure(let diagnostics):
            let first = diagnostics.all.first
            throw AddonActivationError(
                addonID: Self.id, message: first.map { "\($0.field ?? "config"): \($0.message)" } ?? "Invalid config.")
        }
        guard environment.isLoopbackBind || environment.allowUnverifiedJWT else {
            throw AddonActivationError(
                addonID: Self.id,
                message:
                    "verify_signature is false, so any caller can forge a token with any claims, and the server "
                    + "binds to \(environment.hostname), which other machines can reach. Bind to 127.0.0.1, or "
                    + "pass --allow-unverified-jwt if that exposure is intended (e.g. a physical device or "
                    + "Docker; an Android emulator already reaches loopback via 10.0.2.2).")
        }
        if !environment.isLoopbackBind {
            logger.warning(
                "jwt-claims accepts unverified tokens on non-loopback bind \(environment.hostname) (--allow-unverified-jwt)")
        }
    }

    // MARK: - H2

    public func enrich(_ request: AddonRequest) async -> AnyCodableValue? {
        guard let raw = request.header(config.header) else { return nil }
        guard let token = Self.stripScheme(raw, scheme: config.scheme) else {
            logger.debug("jwt-claims: \(config.header) header does not use scheme \(config.scheme)")
            return nil
        }
        guard let claims = Self.decodeClaims(token) else {
            logger.debug("jwt-claims: \(config.header) header is not a decodable JWT")
            return nil
        }
        return claims
    }

    // MARK: - H5

    public func matches(_ spec: AnyCodableValue, facts: AnyCodableValue?) -> Bool {
        guard case .object(let fields) = spec, let expected = fields["claims"], let facts else { return false }
        return Self.contains(facts, expected)
    }

    // MARK: - H7

    public func traceAnnotations(facts: AnyCodableValue?) -> [String: String] {
        guard case .object(let claims)? = facts else { return [:] }
        var annotations: [String: String] = [:]
        for name in config.traceClaims {
            switch claims[name] {
            case .string(let value)?: annotations[name] = value
            case .int(let value)?: annotations[name] = String(value)
            case .double(let value)?: annotations[name] = String(value)
            case .bool(let value)?: annotations[name] = String(value)
            default: continue
            }
        }
        return annotations
    }

    // MARK: - Token decoding

    static func stripScheme(_ header: String, scheme: String) -> String? {
        let trimmed = header.trimmingCharacters(in: .whitespaces)
        guard !scheme.isEmpty else { return trimmed.isEmpty ? nil : trimmed }
        let prefix = scheme + " "
        guard trimmed.count > prefix.count, trimmed.prefix(prefix.count).lowercased() == prefix.lowercased() else {
            return nil
        }
        return String(trimmed.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
    }

    /// Decodes the payload of a JWS compact token (`header.payload.signature`) without checking the
    /// signature. An unsigned token has an empty third segment.
    static func decodeClaims(_ token: String) -> AnyCodableValue? {
        let segments = token.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3, let payload = base64URLDecode(segments[1]) else { return nil }
        guard let claims = try? JSONDecoder().decode(AnyCodableValue.self, from: payload),
            case .object = claims
        else {
            return nil
        }
        return claims
    }

    static func base64URLDecode(_ segment: Substring) -> Data? {
        var base64 = segment.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }

    // MARK: - Matching

    /// Whether `actual` contains `expected`: mappings match when every expected key matches
    /// (extra actual keys are ignored, so a spec names only the claims it cares about); arrays
    /// and scalars must be equal, with integers and doubles compared numerically.
    static func contains(_ actual: AnyCodableValue, _ expected: AnyCodableValue) -> Bool {
        switch (actual, expected) {
        case (.object(let actualFields), .object(let expectedFields)):
            return expectedFields.allSatisfy { key, value in
                actualFields[key].map { contains($0, value) } ?? false
            }
        case (.array(let actualItems), .array(let expectedItems)):
            return actualItems.count == expectedItems.count
                && zip(actualItems, expectedItems).allSatisfy { contains($0, $1) }
        case (.int(let lhs), .double(let rhs)):
            return Double(lhs) == rhs
        case (.double(let lhs), .int(let rhs)):
            return lhs == Double(rhs)
        default:
            return actual == expected
        }
    }

    // MARK: - Config parsing

    struct ConfigDiagnostics: Error {
        let all: [AddonDiagnostic]
    }

    static func parseConfig(_ value: AnyCodableValue) -> Result<Config, ConfigDiagnostics> {
        guard case .object(let fields) = value else {
            return .failure(
                ConfigDiagnostics(all: [AddonDiagnostic(message: "Must be a mapping (set verify_signature: false).")]))
        }
        var config = Config()
        var diagnostics: [AddonDiagnostic] = []
        let known: Set<String> = ["header", "scheme", "verify_signature", "trace_claims"]
        for key in fields.keys.sorted() where !known.contains(key) {
            diagnostics.append(
                AddonDiagnostic(
                    field: key, message: "Unknown key. Supported: \(known.sorted().joined(separator: ", "))."))
        }

        switch fields["verify_signature"] {
        case .bool(false)?:
            break
        case .bool(true)?:
            diagnostics.append(
                AddonDiagnostic(
                    field: "verify_signature",
                    message: "Signature verification is not supported yet. Set verify_signature: false."))
        default:
            diagnostics.append(
                AddonDiagnostic(
                    field: "verify_signature",
                    message:
                        "Required, and must be false: tokens are decoded without checking their signature."))
        }

        switch fields["header"] {
        case nil: break
        case .string(let header)? where !header.trimmingCharacters(in: .whitespaces).isEmpty:
            config.header = header
        default:
            diagnostics.append(AddonDiagnostic(field: "header", message: "Must be a non-empty header name."))
        }

        switch fields["scheme"] {
        case nil: break
        case .string(let scheme)? where !scheme.contains(" "):
            config.scheme = scheme
        default:
            diagnostics.append(
                AddonDiagnostic(field: "scheme", message: "Must be a single word (e.g. Bearer), or \"\" for none."))
        }

        switch fields["trace_claims"] {
        case nil: break
        case .array(let items)?:
            let names = items.compactMap { item -> String? in
                if case .string(let name) = item, !name.isEmpty { return name }
                return nil
            }
            if names.count == items.count {
                config.traceClaims = names
            } else {
                diagnostics.append(
                    AddonDiagnostic(field: "trace_claims", message: "Must be a list of non-empty claim names."))
            }
        default:
            diagnostics.append(
                AddonDiagnostic(field: "trace_claims", message: "Must be a list of non-empty claim names."))
        }

        return diagnostics.isEmpty ? .success(config) : .failure(ConfigDiagnostics(all: diagnostics))
    }
}
