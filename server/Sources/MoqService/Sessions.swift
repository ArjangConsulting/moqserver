import Foundation

/// A handle-keyed table of `ProjectSession`s.
///
/// `moq-mcp` uses exactly one, implicitly, matching its one-agent-one-process model. A JSON-RPC
/// client such as Studio can hold several — one per open project window — against a single
/// long-lived `moq-format` process.
public actor Sessions {
    private var sessions: [String: ProjectSession] = [:]

    public init() {}

    /// Creates a new session and returns its handle.
    public func open() -> String {
        let handle = UUID().uuidString
        sessions[handle] = ProjectSession()
        return handle
    }

    public func session(_ handle: String) throws -> ProjectSession {
        guard let session = sessions[handle] else {
            throw MoqServiceError.unknownSession(handle)
        }
        return session
    }

    @discardableResult
    public func close(_ handle: String) -> Bool {
        sessions.removeValue(forKey: handle) != nil
    }
}
