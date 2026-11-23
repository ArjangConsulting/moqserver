import Foundation

extension MoqClient {
    /// The same calls without blocking a thread, for `async` tests (Swift Testing, async XCTest).
    public var asyncClient: MoqAsyncClient { MoqAsyncClient(client: self) }
}

/// Async counterpart of every `MoqClient` call. Cancelling the calling task cancels the request.
public struct MoqAsyncClient: Sendable {
    public let client: MoqClient

    public init(client: MoqClient) {
        self.client = client
    }

    // MARK: - Sessions

    /// Creates an isolated session and returns a client scoped to it.
    public func createSession() async throws -> MoqClient {
        let data = try await client.sendAsync("POST", url: client.sessionsURL)
        guard let id = try MoqClient.decode([String: String].self, from: data)["id"] else {
            throw MoqControlError.transport("Missing session ID")
        }
        return client.scoped(to: id)
    }

    public func closeSession() async throws {
        guard let sessionID = client.sessionID else { return }
        _ = try await client.sendAsync("DELETE", url: client.sessionsURL.appendingPathComponent(sessionID))
    }

    public func sessions() async throws -> [MoqSession] {
        try MoqClient.decode([MoqSession].self, from: await client.sendAsync("GET", url: client.sessionsURL))
    }

    // MARK: - Variants and state

    public func selectVariant(
        _ variant: String, for method: String, path: String, operation: String? = nil
    )
        async throws
    {
        _ = try await client.sendAsync(
            "PUT", url: client.adminURL(method: method, path: path, subresource: "variant", operation: operation),
            json: MoqClient.json(["variant": variant]))
    }

    public func resetVariant(for method: String, path: String, operation: String? = nil) async throws {
        _ = try await client.sendAsync(
            "DELETE", url: client.adminURL(method: method, path: path, subresource: "variant", operation: operation))
    }

    public func resetCallCount(for method: String, path: String, operation: String? = nil) async throws {
        _ = try await client.sendAsync(
            "DELETE",
            url: client.adminURL(method: method, path: path, subresource: "call-count", operation: operation))
    }

    public func resetAll(for method: String, path: String, operation: String? = nil) async throws {
        _ = try await client.sendAsync(
            "DELETE", url: client.adminURL(method: method, path: path, subresource: "state", operation: operation))
    }

    public func resetAll() async throws {
        _ = try await client.sendAsync("DELETE", url: client.baseURL.appendingPathComponent("_admin/state"))
    }

    public func callCount(for method: String, path: String, operation: String? = nil) async throws -> Int {
        try MoqClient.callCount(
            from: await client.sendAsync(
                "GET", url: client.adminURL(method: method, path: path, subresource: "", operation: operation)))
    }

    public func endpoints() async throws -> [MoqEndpoint] {
        try MoqClient.decode([MoqEndpoint].self, from: await client.sendAsync("GET", url: client.endpointsURL))
    }

    // MARK: - Scenarios

    public func activateScenario(_ name: String) async throws {
        _ = try await client.sendAsync(
            "PUT", url: client.baseURL.appendingPathComponent("_admin/scenario"), json: MoqClient.scenarioName(name))
    }

    public func scenarios() async throws -> [MoqScenario] {
        try MoqClient.decode([MoqScenario].self, from: await client.sendAsync("GET", url: client.scenariosURL))
    }

    public func defineScenario(_ name: String, overrides: [String: String]) async throws {
        _ = try await client.sendAsync(
            "PUT", url: client.scenariosURL, json: MoqClient.json(MoqScenario(name: name, overrides: overrides)))
    }

    public func removeScenario(_ name: String) async throws {
        _ = try await client.sendAsync("DELETE", url: client.scenariosURL, json: MoqClient.scenarioName(name))
    }

    @discardableResult
    public func reload() async throws -> MoqReloadSummary {
        try MoqClient.decode(MoqReloadSummary.self, from: await client.sendAsync("POST", url: client.reloadURL))
    }

    // MARK: - Request history

    public func requests() async throws -> [MoqRequestRecord] {
        try MoqClient.decode(
            [MoqRequestRecord].self,
            from: await client.sendAsync("GET", url: client.baseURL.appendingPathComponent("_admin/requests")))
    }

    public func unmatchedRequests() async throws -> [MoqRequestRecord] {
        try await query(MoqRequestQuery(unmatched: true)).matched
    }

    public func clearRequests() async throws {
        _ = try await client.sendAsync("DELETE", url: client.baseURL.appendingPathComponent("_admin/requests"))
    }

    public func assertNoUnmatchedRequests() async throws {
        let unmatched = try await unmatchedRequests()
        if !unmatched.isEmpty { throw MoqUnmatchedRequestsError(requests: unmatched) }
    }

    // MARK: - Readiness

    /// Polls the admin API until the server answers or `timeout` elapses.
    public func waitUntilReady(timeout: TimeInterval = 10) async -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + max(0, timeout)
        while ProcessInfo.processInfo.systemUptime < deadline {
            if (try? await client.sendAsync("GET", url: client.endpointsURL)) != nil { return true }
            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            guard remaining > 0 else { break }
            try? await Task.sleep(nanoseconds: UInt64(min(0.2, remaining) * 1_000_000_000))
        }
        return false
    }
}
