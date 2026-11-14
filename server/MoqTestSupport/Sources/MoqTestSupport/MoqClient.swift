import Foundation

public enum MoqControlError: Error, Equatable, Sendable {
    case timedOut
    case transport(String)
    case rejected(status: Int, body: String)
}

/// One immutable configuration per test suite. Separate clients may safely target separate
/// server processes in parallel. A timeout cancels the request; it cannot undo a mutation the
/// server has already accepted, so use a separate server per concurrently running suite.
public final class MoqClient: Sendable {
    public enum Auth: Sendable {
        case bearer(String)
        case apiKey(header: String, value: String)
    }

    public let baseURL: URL
    private let auth: Auth?
    private let session: URLSession
    private let timeout: TimeInterval
    public let sessionID: String?

    public init(
        baseURL: URL, auth: Auth? = nil, session: URLSession = .shared, timeout: TimeInterval = 10,
        sessionID: String? = nil
    ) {
        self.baseURL = baseURL
        self.sessionID = sessionID
        self.auth = auth
        self.session = session
        self.timeout = max(0.001, timeout)
    }

    public func createSession() throws -> MoqClient {
        let data = try send("POST", url: baseURL.appendingPathComponent("_admin/sessions"))
        let result = try JSONDecoder().decode([String: String].self, from: data)
        guard let id = result["id"] else { throw MoqControlError.transport("Missing session ID") }
        return scoped(to: id)
    }

    func scoped(to sessionID: String) -> MoqClient {
        MoqClient(baseURL: baseURL, auth: auth, session: session, timeout: timeout, sessionID: sessionID)
    }

    public func closeSession() throws {
        guard let sessionID else { return }
        try send("DELETE", url: baseURL.appendingPathComponent("_admin/sessions/\(sessionID)"))
    }

    public func activateScenario(_ name: String) throws {
        try send("PUT", url: baseURL.appendingPathComponent("_admin/scenario"), json: Self.scenarioName(name))
    }

    public func resetAll() throws {
        try send("DELETE", url: baseURL.appendingPathComponent("_admin/state"))
    }

    /// Makes `variant` the active response. For a GraphQL operation on a shared route, pass its
    /// `operation` (the name, or the selector listed by ``endpoints()``).
    public func selectVariant(_ variant: String, for method: String, path: String, operation: String? = nil) throws {
        try send(
            "PUT", url: adminURL(method: method, path: path, subresource: "variant", operation: operation),
            json: Self.json(["variant": variant]))
    }

    public func resetVariant(for method: String, path: String, operation: String? = nil) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "variant", operation: operation))
    }

    public func resetCallCount(for method: String, path: String, operation: String? = nil) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "call-count", operation: operation))
    }

    public func resetAll(for method: String, path: String, operation: String? = nil) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "state", operation: operation))
    }

    public func waitUntilReady(timeout: TimeInterval = 10) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + max(0, timeout)
        while ProcessInfo.processInfo.systemUptime < deadline {
            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            do {
                try send("GET", url: baseURL.appendingPathComponent("_admin/endpoints"), timeout: min(2, remaining))
                return true
            } catch {
                let remaining = deadline - ProcessInfo.processInfo.systemUptime
                if remaining > 0 { Thread.sleep(forTimeInterval: min(0.2, remaining)) }
            }
        }
        return false
    }

    /// `subresource` is empty for the endpoint itself (`GET` its detail).
    func adminURL(method: String, path: String, subresource: String, operation: String? = nil) -> URL {
        let normalized = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var url = baseURL.appendingPathComponent("_admin/endpoints")
            .appendingPathComponent(method.uppercased()).appendingPathComponent(normalized)
        if !subresource.isEmpty { url.appendPathComponent(subresource) }
        guard let operation,
            var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return url }
        components.queryItems = [URLQueryItem(name: "operation", value: operation)]
        return components.url ?? url
    }

    static func json(_ value: some Encodable) throws -> Data {
        try JSONEncoder().encode(value)
    }

    func makeRequest(_ method: String, url: URL, json: Data?, timeout: TimeInterval) -> URLRequest {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        if let sessionID { request.setValue(sessionID, forHTTPHeaderField: "X-Mock-Session") }
        switch auth {
        case .bearer(let token): request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .apiKey(let header, let value): request.setValue(value, forHTTPHeaderField: header)
        case .none: break
        }
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = json
        }
        return request
    }

    /// Maps a finished `URLSession` task to its body, or a typed `MoqControlError`.
    static func validate(data: Data?, response: URLResponse?, error: Error?) throws -> Data {
        if let error {
            if (error as? URLError)?.code == .timedOut { throw MoqControlError.timedOut }
            throw MoqControlError.transport(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw MoqControlError.rejected(status: status, body: String(decoding: data ?? Data(), as: UTF8.self))
        }
        return data ?? Data()
    }

    /// Blocking transport: waits at most `timeout` in total, then cancels the task.
    @discardableResult
    func send(_ method: String, url: URL, json: Data? = nil, timeout: TimeInterval? = nil) throws -> Data {
        let duration = timeout ?? self.timeout
        let request = makeRequest(method, url: url, json: json, timeout: duration)
        let completion = RequestCompletion()
        let task = session.dataTask(with: request) { data, response, error in
            completion.finish(data: data, response: response, error: error)
        }
        task.resume()
        guard let result = completion.wait(timeout: duration) else {
            task.cancel()
            throw MoqControlError.timedOut
        }
        return try Self.validate(data: result.data, response: result.response, error: result.error)
    }

    /// Non-blocking transport for `MoqAsyncClient`. Cancelling the calling task cancels the request;
    /// `timeout` bounds idle time between bytes (URLSession's request timeout).
    func sendAsync(_ method: String, url: URL, json: Data? = nil) async throws -> Data {
        let request = makeRequest(method, url: url, json: json, timeout: timeout)
        let handle = TaskHandle()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.dataTask(with: request) { data, response, error in
                    continuation.resume(
                        with: Result { try Self.validate(data: data, response: response, error: error) })
                }
                handle.start(task)
            }
        } onCancel: {
            handle.cancel()
        }
    }
}

/// All result access is protected by the condition, including a completion racing a timeout.
private final class RequestCompletion: @unchecked Sendable {
    struct Result {
        let data: Data?
        let response: URLResponse?
        let error: Error?
    }
    private let condition = NSCondition()
    private var result: Result?

    func finish(data: Data?, response: URLResponse?, error: Error?) {
        condition.lock()
        defer { condition.unlock() }
        result = Result(data: data, response: response, error: error)
        condition.signal()
    }

    func wait(timeout: TimeInterval) -> Result? {
        condition.lock()
        defer { condition.unlock() }
        let deadline = Date().addingTimeInterval(timeout)
        while result == nil {
            if !condition.wait(until: deadline) { return result }
        }
        return result
    }
}

/// Lets a cancellation that races task creation still cancel the task.
private final class TaskHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionTask?
    private var cancelled = false

    func start(_ task: URLSessionTask) {
        lock.lock()
        self.task = task
        let cancelled = self.cancelled
        lock.unlock()
        if cancelled { task.cancel() } else { task.resume() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let task = self.task
        lock.unlock()
        task?.cancel()
    }
}
