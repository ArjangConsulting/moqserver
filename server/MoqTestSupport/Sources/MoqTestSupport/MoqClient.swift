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
        return MoqClient(baseURL: baseURL, auth: auth, session: session, timeout: timeout, sessionID: id)
    }

    public func closeSession() throws {
        guard let sessionID else { return }
        try send("DELETE", url: baseURL.appendingPathComponent("_admin/sessions/\(sessionID)"))
    }

    public func activateScenario(_ name: String) throws {
        try send("PUT", url: baseURL.appendingPathComponent("_admin/scenario"), body: ["name": name])
    }

    public func resetAll() throws {
        try send("DELETE", url: baseURL.appendingPathComponent("_admin/state"))
    }

    public func selectVariant(_ variant: String, for method: String, path: String) throws {
        try send("PUT", url: adminURL(method: method, path: path, subresource: "variant"), body: ["variant": variant])
    }

    public func resetVariant(for method: String, path: String) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "variant"))
    }

    public func resetCallCount(for method: String, path: String) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "call-count"))
    }

    public func resetAll(for method: String, path: String) throws {
        try send("DELETE", url: adminURL(method: method, path: path, subresource: "state"))
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

    func adminURL(method: String, path: String, subresource: String) -> URL {
        let normalized = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return baseURL.appendingPathComponent("_admin/endpoints")
            .appendingPathComponent(method.uppercased()).appendingPathComponent(normalized)
            .appendingPathComponent(subresource)
    }

    @discardableResult
    func send(_ method: String, url: URL, body: [String: String]? = nil, timeout: TimeInterval? = nil) throws -> Data {
        let duration = timeout ?? self.timeout
        var request = URLRequest(url: url, timeoutInterval: duration)
        request.httpMethod = method
        if let sessionID { request.setValue(sessionID, forHTTPHeaderField: "X-Mock-Session") }
        switch auth {
        case .bearer(let token): request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .apiKey(let header, let value): request.setValue(value, forHTTPHeaderField: header)
        case .none: break
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let completion = RequestCompletion()
        let task = session.dataTask(with: request) { data, response, error in
            completion.finish(data: data, response: response, error: error)
        }
        task.resume()
        guard let result = completion.wait(timeout: duration) else {
            task.cancel()
            throw MoqControlError.timedOut
        }
        if let error = result.error {
            if (error as? URLError)?.code == .timedOut { throw MoqControlError.timedOut }
            throw MoqControlError.transport(error.localizedDescription)
        }
        let status = (result.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw MoqControlError.rejected(status: status, body: String(decoding: result.data ?? Data(), as: UTF8.self))
        }
        return result.data ?? Data()
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
