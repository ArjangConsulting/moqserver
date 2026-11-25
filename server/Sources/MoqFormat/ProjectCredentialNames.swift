import MoqAddonKit
import MoqAddons
import MoqCore

extension CredentialNames {
    /// The credential names for serving or importing into `project`: the built-in lists, its
    /// `redact:` section, the headers it declares as credentials (`defaults.auth.header_name`, each
    /// endpoint's `auth.header_name`, and each enabled add-on's credential header), and the
    /// caller's extra and excepted names (command-line flags).
    public static func forProject(
        _ project: MoqProject?, catalog: AddonCatalog = .builtIn, extraHeaders: [String] = [],
        extraQueryParameters: [String] = [], exceptHeaders: [String] = [], exceptQueryParameters: [String] = []
    ) -> CredentialNames {
        // Typed locals: one generic call over four `+`/`??` expressions times out the Linux type checker.
        let redact = project?.manifest.redact
        let headers: [String] = extraHeaders + (redact?.headers ?? [])
        let queryParameters: [String] = extraQueryParameters + (redact?.queryParameters ?? [])
        let excepted: [String] = exceptHeaders + (redact?.except?.headers ?? [])
        let exceptedQuery: [String] = exceptQueryParameters + (redact?.except?.queryParameters ?? [])
        let names = CredentialNames(
            extraHeaders: headers, extraQueryParameters: queryParameters, exceptHeaders: excepted,
            exceptQueryParameters: exceptedQuery)
        guard let project else { return names }
        return names.adding(headers: declaredCredentialHeaders(project, catalog: catalog))
    }

    /// Headers the bundle itself says carry a credential.
    static func declaredCredentialHeaders(_ project: MoqProject, catalog: AddonCatalog) -> [String] {
        var declared: [String] = []
        let auths = [project.manifest.defaults.auth] + project.endpoints.compactMap(\.auth)
        for auth in auths where auth.type != .none {
            if let header = auth.headerName { declared.append(header) }
        }
        for (id, config) in project.manifest.addons ?? [:] {
            declared += catalog.addonType(for: id)?.credentialHeaders(config: config) ?? []
        }
        return declared
    }
}
