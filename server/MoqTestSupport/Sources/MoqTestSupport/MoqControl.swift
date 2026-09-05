import Foundation
import XCTest

/// Drives a running `moqserver` instance from a UI or integration test, so one test can walk a
/// screen (or flow) through several backend scenarios — a failure, then a successful retry —
/// inside a single run.
///
/// The app under test sends its own headers, so `X-Mock-Variant` cannot select a scenario from
/// the test side. moqserver's admin API can — it's enabled by default and unauthenticated on
/// loopback, which is exactly the mocked-suite setup (see `moqserver-scenario-design` in
/// `server/skills/` for when to reach for this versus a bundle's own `call_count`).
///
/// Call counters and variant overrides live in the running server process, not in the bundle, so
/// **a test that sets an override must clear it** — otherwise it leaks into whatever runs next.
/// ``resetAll(for:path:)`` in `tearDown` is the safe default.
///
/// ```swift
/// try MoqControl.selectVariant("serverError", for: "GET", path: "/v1/videos/1440/")
/// // …assert the error state…
/// try MoqControl.resetVariant(for: "GET", path: "/v1/videos/1440/")
/// // …tap Retry, assert content…
/// ```
public enum MoqControl {

    /// Base URL of the `moqserver` instance under test. Mutable per-suite (e.g. a different port
    /// per parallel test bundle) — set it once in a test plan's setup, not per call, and don't
    /// mutate it concurrently from multiple tests.
    public static var baseURL = URL(string: "http://127.0.0.1:8080")!

    /// Credentials to attach when the target server's admin API requires them (see
    /// `docs/ADMIN_API.md`'s `admin.bearerToken`/`admin.apiKey` config). `nil` (the default) sends
    /// no admin credentials, matching moqserver's default of open, unauthenticated admin routes.
    public static var adminAuth: AdminAuth?

    public enum AdminAuth {
        case bearer(String)
        case apiKey(header: String, value: String)
    }

    // MARK: - Variant selection

    /// Makes `variant` the active response for `method path` until it is reset.
    public static func selectVariant(
        _ variant: String,
        for method: String,
        path: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        try send(
            .put,
            adminURL(method: method, path: path, subresource: "variant"),
            body: ["variant": variant],
            file: file,
            line: line
        )
    }

    /// Restores the endpoint's declared default variant.
    public static func resetVariant(
        for method: String,
        path: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        try send(.delete, adminURL(method: method, path: path, subresource: "variant"), file: file, line: line)
    }

    /// Resets the endpoint's call counter, so `call_count`-scoped variants start from call 1 again.
    public static func resetCallCount(
        for method: String,
        path: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        try send(.delete, adminURL(method: method, path: path, subresource: "call-count"), file: file, line: line)
    }

    /// Clears both the variant override and the call counter. Safe to call when nothing was set.
    public static func resetAll(for method: String, path: String) {
        try? resetVariant(for: method, path: path)
        try? resetCallCount(for: method, path: path)
    }

    // MARK: - GET convenience

    /// Convenience for the common case of a GET-only mocked endpoint.
    public static func selectVariant(
        _ variant: String, forGET path: String, file: StaticString = #filePath, line: UInt = #line
    ) throws {
        try selectVariant(variant, for: "GET", path: path, file: file, line: line)
    }

    /// Convenience for the common case of a GET-only mocked endpoint.
    public static func resetVariant(forGET path: String, file: StaticString = #filePath, line: UInt = #line) throws {
        try resetVariant(for: "GET", path: path, file: file, line: line)
    }

    /// Convenience for the common case of a GET-only mocked endpoint.
    public static func resetCallCount(forGET path: String, file: StaticString = #filePath, line: UInt = #line) throws
    {
        try resetCallCount(for: "GET", path: path, file: file, line: line)
    }

    /// Convenience for the common case of a GET-only mocked endpoint.
    public static func resetAll(forGET path: String) {
        resetAll(for: "GET", path: path)
    }

    // MARK: - Readiness

    /// Polls `GET /_admin/endpoints` until it succeeds or `timeout` elapses — use in test-plan
    /// setup to wait for a `moqserver serve` process launched alongside the test run, instead of a
    /// fixed sleep. Returns `false` (does not fail the test itself) on timeout, so callers can
    /// decide whether that's fatal.
    @discardableResult
    public static func waitUntilReady(timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        var request = URLRequest(url: baseURL.appendingPathComponent("_admin/endpoints"))
        applyAuth(&request)
        while Date() < deadline {
            if let status = synchronousStatusCode(for: request), (200..<300).contains(status) {
                return true
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return false
    }

    // MARK: - Private

    private enum Method: String { case put = "PUT", delete = "DELETE" }

    /// `/_admin/endpoints/<METHOD>/<path>/<subresource>` — the path is embedded without its
    /// leading slash. A trailing slash on the endpoint path is fine: moqserver resolves both
    /// spellings to the same endpoint.
    /// `internal` (not `private`) so `MoqControlTests` can exercise URL construction directly via
    /// `@testable import` without a live server.
    static func adminURL(method: String, path: String, subresource: String) -> URL {
        let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let normalized = trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
        return
            baseURL
            .appendingPathComponent("_admin/endpoints")
            .appendingPathComponent(method.uppercased())
            .appendingPathComponent(normalized)
            .appendingPathComponent(subresource)
    }

    private static func applyAuth(_ request: inout URLRequest) {
        switch adminAuth {
        case .none:
            break
        case .bearer(let token):
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .apiKey(let header, let value):
            request.setValue(value, forHTTPHeaderField: header)
        }
    }

    private static func synchronousStatusCode(for request: URLRequest) -> Int? {
        var status: Int?
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 2)
        return status
    }

    private static func send(
        _ method: Method,
        _ url: URL,
        body: [String: String]? = nil,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        applyAuth(&request)
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        var result: (Data?, URLResponse?, (any Error)?)
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, error in
            result = (data, response, error)
            done.signal()
        }.resume()

        guard done.wait(timeout: .now() + 10) == .success else {
            XCTFail(
                "moqserver admin call timed out: \(method.rawValue) \(url.path)",
                file: file,
                line: line
            )
            return
        }
        if let error = result.2 {
            XCTFail(
                "moqserver admin call failed: \(method.rawValue) \(url.path) — \(error.localizedDescription). "
                    + "Is a moqserver instance running at \(baseURL)?",
                file: file,
                line: line
            )
            return
        }
        guard let status = (result.1 as? HTTPURLResponse)?.statusCode, (200..<300).contains(status)
        else {
            let detail = result.0.flatMap { String(data: $0, encoding: .utf8) } ?? "<no body>"
            XCTFail(
                "moqserver admin call rejected: \(method.rawValue) \(url.path) — \(detail)",
                file: file,
                line: line
            )
            return
        }
    }
}
