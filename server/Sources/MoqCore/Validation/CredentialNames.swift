import Foundation

/// Which request header and query parameter names carry credentials, so their values are never
/// stored or echoed back. Request history redacts them, validation messages hide them, and the HAR
/// importer strips them.
///
/// A name is a credential only when it is on a list; nothing is guessed from fragments of a name,
/// so ordinary names such as `keyword`, `sort_key` or `X-Author-Id` stay visible. The lists are:
/// - the built-in ``builtInHeaders`` and ``builtInQueryParameters``, each a name with no
///   non-credential meaning;
/// - names a bundle declares under `redact:` in `project.yml`, and the headers it already says
///   carry credentials (an endpoint's `auth.header_name`, an add-on's credential header);
/// - names given on the command line (`--redact-header`, `--redact-query-parameter`).
///
/// A bundle or flag can also exempt a name, except the four ``protectedHeaders``. An exempt name is
/// never redacted. Any other name whose value contains a JWT is redacted too, so a token sent under
/// a name nobody listed is still caught.
///
/// Names compare after ``normalize(_:)``: lowercased, with `-` and `_` removed, so `api_key`,
/// `apiKey` and `API-KEY` are the same name.
public struct CredentialNames: Sendable, Equatable {
    /// Built-in credential headers, normalized.
    public static let builtInHeaders: Set<String> = normalizedSet([
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
        "X-Authorization",
        "X-Api-Key", "Api-Key",
        "X-Auth-Token", "X-Access-Token", "X-Refresh-Token", "X-Id-Token",
        "X-CSRF-Token", "X-XSRF-Token",
        "X-Amz-Security-Token", "X-Goog-Api-Key", "X-Firebase-AppCheck", "Ocp-Apim-Subscription-Key",
    ])

    /// Built-in credential query parameters, normalized: OAuth and OpenID Connect token and secret
    /// parameters, API keys, and signed-URL signatures.
    public static let builtInQueryParameters: Set<String> = normalizedSet([
        "access_token", "refresh_token", "id_token", "id_token_hint", "client_secret", "client_assertion",
        "assertion", "code_verifier", "device_code", "subject_token", "actor_token", "token", "password",
        "api_key", "SAMLResponse", "X-Amz-Signature", "X-Amz-Security-Token", "X-Goog-Signature",
    ])

    /// Built-in headers no bundle or flag can exempt.
    public static let protectedHeaders: Set<String> = normalizedSet([
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
    ])

    /// Matches a JWT (header.payload.signature) anywhere in a value. Both parts start `eyJ` (base64
    /// of `{"`); the signature may be empty, as in unsigned emulator tokens. Groups 1 and 2 are the
    /// header and payload, which HAR import keeps when it strips the signature.
    public static let jwtPattern =
        #"(?<![A-Za-z0-9_-])(eyJ[A-Za-z0-9_-]{4,}=*)\.(eyJ[A-Za-z0-9_-]{4,}=*)\.[A-Za-z0-9\-_.+/=]*"#

    /// Only the built-in lists.
    public static let builtIn = CredentialNames()

    /// Effective credential header names, normalized.
    public let headers: Set<String>
    /// Effective credential query parameter names, normalized.
    public let queryParameters: Set<String>
    /// Header names the caller exempted, normalized; never redacted, even with a JWT value.
    public let exemptHeaders: Set<String>
    /// Query parameter names the caller exempted, normalized; never redacted, even with a JWT value.
    public let exemptQueryParameters: Set<String>

    /// The built-in lists plus `extraHeaders` and `extraQueryParameters`, minus `exceptHeaders` and
    /// `exceptQueryParameters`. A protected header in `exceptHeaders` stays a credential.
    public init(
        extraHeaders: some Sequence<String> = [String](), extraQueryParameters: some Sequence<String> = [String](),
        exceptHeaders: some Sequence<String> = [String](), exceptQueryParameters: some Sequence<String> = [String]()
    ) {
        let exemptHeaders = Self.normalizedSet(exceptHeaders).subtracting(Self.protectedHeaders)
        let exemptQueryParameters = Self.normalizedSet(exceptQueryParameters)
        self.init(
            headers: Self.builtInHeaders.union(Self.normalizedSet(extraHeaders)).subtracting(exemptHeaders),
            queryParameters: Self.builtInQueryParameters.union(Self.normalizedSet(extraQueryParameters))
                .subtracting(exemptQueryParameters),
            exemptHeaders: exemptHeaders, exemptQueryParameters: exemptQueryParameters)
    }

    private init(
        headers: Set<String>, queryParameters: Set<String>, exemptHeaders: Set<String>,
        exemptQueryParameters: Set<String>
    ) {
        self.headers = headers
        self.queryParameters = queryParameters
        self.exemptHeaders = exemptHeaders
        self.exemptQueryParameters = exemptQueryParameters
    }

    /// These names plus `headers` and `queryParameters`, which stop being exempt.
    public func adding(
        headers: some Sequence<String>, queryParameters: some Sequence<String> = [String]()
    )
        -> CredentialNames
    {
        let addedHeaders = Self.normalizedSet(headers)
        let addedQueryParameters = Self.normalizedSet(queryParameters)
        return CredentialNames(
            headers: self.headers.union(addedHeaders),
            queryParameters: self.queryParameters.union(addedQueryParameters),
            exemptHeaders: exemptHeaders.subtracting(addedHeaders),
            exemptQueryParameters: exemptQueryParameters.subtracting(addedQueryParameters))
    }

    /// Whether header `name` is a credential by name alone.
    public func isCredentialHeader(_ name: String) -> Bool { headers.contains(Self.normalize(name)) }

    /// Whether header `name` was exempted, so its value is kept whatever it holds.
    public func isExemptHeader(_ name: String) -> Bool { exemptHeaders.contains(Self.normalize(name)) }

    /// Whether the header is a credential by name, or, unless exempt, because its value holds a JWT.
    public func isCredentialHeader(_ name: String, value: String?) -> Bool {
        if isCredentialHeader(name) { return true }
        return !isExemptHeader(name) && value.map(Self.containsJWT) == true
    }

    /// Whether query parameter `name` is a credential by name alone.
    public func isCredentialQueryParameter(_ name: String) -> Bool {
        queryParameters.contains(Self.normalize(name))
    }

    /// Whether query parameter `name` was exempted, so its value is kept whatever it holds.
    public func isExemptQueryParameter(_ name: String) -> Bool {
        exemptQueryParameters.contains(Self.normalize(name))
    }

    /// Whether the query parameter is a credential by name, or, unless exempt, because its value
    /// holds a JWT.
    public func isCredentialQueryParameter(_ name: String, value: String?) -> Bool {
        if isCredentialQueryParameter(name) { return true }
        return !isExemptQueryParameter(name) && value.map(Self.containsJWT) == true
    }

    /// Lowercased, trimmed, with `-` and `_` removed.
    public static func normalize(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).lowercased().filter { $0 != "-" && $0 != "_" }
    }

    /// Whether `value` contains a JWT.
    public static func containsJWT(_ value: String) -> Bool {
        value.range(of: jwtPattern, options: .regularExpression) != nil
    }

    static func normalizedSet(_ names: some Sequence<String>) -> Set<String> {
        Set(names.map(normalize).filter { !$0.isEmpty })
    }
}
