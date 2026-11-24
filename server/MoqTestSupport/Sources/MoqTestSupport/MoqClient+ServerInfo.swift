import Foundation

/// What `GET /_admin/version` reports about the server.
public struct MoqServerInfo: Codable, Equatable, Sendable {
    /// The release tag, or `nil` for a server built from source.
    public let version: String?
    /// The commit a release was built from, or `nil` for a server built from source.
    public let commit: String?
    /// Admin-API behaviours the server supports, such as `request-query`.
    public let capabilities: [String]

    public init(version: String? = nil, commit: String? = nil, capabilities: [String]) {
        self.version = version
        self.commit = commit
        self.capabilities = capabilities
    }

    public func supports(_ capability: String) -> Bool { capabilities.contains(capability) }
}

/// Thrown by `requireCapabilities` when the server lacks behaviour the caller depends on.
public struct MoqMissingCapabilitiesError: Error, CustomStringConvertible, Sendable {
    public let missing: [String]
    /// `nil` when the server predates `GET /_admin/version`.
    public let server: MoqServerInfo?

    public var description: String {
        let found = server.map { "\($0.version ?? "an unreleased build")" } ?? "a server without /_admin/version"
        return "moqserver lacks \(missing.joined(separator: ", ")) (found \(found)); upgrade the server."
    }
}

extension MoqClient {
    var versionURL: URL { baseURL.appendingPathComponent("_admin/version") }

    /// The server's release, commit and capabilities.
    public func serverInfo() throws -> MoqServerInfo {
        try Self.decode(MoqServerInfo.self, from: send("GET", url: versionURL))
    }

    /// Throws ``MoqMissingCapabilitiesError`` unless the server supports every capability named.
    public func requireCapabilities(_ capabilities: String...) throws {
        try Self.check(capabilities, against: Result { try serverInfo() })
    }

    static func check(_ capabilities: [String], against result: Result<MoqServerInfo, Error>) throws {
        let info: MoqServerInfo?
        switch result {
        case .success(let value): info = value
        case .failure(MoqControlError.rejected(status: 404, _)): info = nil
        case .failure(let error): throw error
        }
        let missing = capabilities.filter { info?.supports($0) != true }
        if !missing.isEmpty { throw MoqMissingCapabilitiesError(missing: missing, server: info) }
    }
}

extension MoqAsyncClient {
    public func serverInfo() async throws -> MoqServerInfo {
        try MoqClient.decode(MoqServerInfo.self, from: await client.sendAsync("GET", url: client.versionURL))
    }

    public func requireCapabilities(_ capabilities: String...) async throws {
        let result: Result<MoqServerInfo, Error>
        do { result = .success(try await serverInfo()) } catch { result = .failure(error) }
        try MoqClient.check(capabilities, against: result)
    }
}
