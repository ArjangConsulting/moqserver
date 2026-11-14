import Foundation
import XCTest

@testable import MoqTestSupport

final class MoqClientInspectionTests: XCTestCase {
    private func client() -> MoqClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RoutingStub.self]
        return MoqClient(
            baseURL: URL(string: "http://localhost:9999")!, session: URLSession(configuration: config), timeout: 1)
    }

    override func setUp() {
        super.setUp()
        RoutingStub.reset()
    }

    func testOperationIsSentAsQueryItem() throws {
        try client().selectVariant("error", for: "POST", path: "/graphql", operation: "GetUser")
        XCTAssertEqual(RoutingStub.lastURL?.path, "/_admin/endpoints/POST/graphql/variant")
        XCTAssertEqual(RoutingStub.lastURL?.query, "operation=GetUser")
    }

    func testEndpointsAndCallCountDecode() throws {
        let endpoints = try client().endpoints()
        XCTAssertEqual(endpoints.map(\.key), ["GET /users", "POST /graphql#GetUser"])
        XCTAssertEqual(endpoints.last?.operation, "GetUser")
        XCTAssertEqual(try client().callCount(for: "GET", path: "/users"), 3)
        XCTAssertEqual(RoutingStub.lastURL?.path, "/_admin/endpoints/GET/users")
    }

    func testDefineScenarioSendsOverrides() throws {
        try client().defineScenario("empty", overrides: ["GET /users": "empty"])
        let body = try XCTUnwrap(RoutingStub.lastBody)
        XCTAssertEqual(try JSONDecoder().decode(MoqScenario.self, from: body).overrides, ["GET /users": "empty"])
        XCTAssertEqual(try client().scenarios().map(\.name), ["empty"])
    }

    func testAsyncClientMirrorsBlockingCalls() async throws {
        let async = client().asyncClient
        let scoped = try await async.createSession()
        XCTAssertEqual(scoped.sessionID, "s-1")
        let count = try await async.callCount(for: "GET", path: "/users")
        XCTAssertEqual(count, 3)
        let sessions = try await async.sessions()
        XCTAssertEqual(sessions.first?.expiresAt, 1_800)
        let summary = try await async.reload()
        XCTAssertEqual(summary.endpoints, 2)
        try await scoped.asyncClient.resetAll()
        XCTAssertEqual(RoutingStub.lastSession, "s-1")
    }

    func testAsyncRejectionIsTyped() async {
        do {
            try await client().asyncClient.selectVariant("x", for: "GET", path: "/rejected")
            XCTFail("expected rejection")
        } catch {
            XCTAssertEqual(error as? MoqControlError, .rejected(status: 404, body: "missing"))
        }
    }
}

private final class RoutingStub: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var state: (url: URL?, body: Data?, session: String?) = (nil, nil, nil)

    static var lastURL: URL? { lock.withLock { state.url } }
    static var lastBody: Data? { lock.withLock { state.body } }
    static var lastSession: String? { lock.withLock { state.session } }
    static func reset() { lock.withLock { state = (nil, nil, nil) } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read)
        Self.lock.withLock {
            Self.state = (url, body, request.value(forHTTPHeaderField: "X-Mock-Session"))
        }
        let (status, payload) = Self.respond(method: request.httpMethod ?? "GET", path: url.path)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(payload.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func respond(method: String, path: String) -> (Int, String) {
        switch (method, path) {
        case ("GET", "/_admin/endpoints"):
            return (
                200,
                #"[{"method":"GET","path":"/users","key":"GET /users","variants":["ok"],"currentCallCount":3},"#
                    + #"{"method":"POST","path":"/graphql","key":"POST /graphql#GetUser","operation":"GetUser","#
                    + #""variants":["ok"],"currentCallCount":0}]"#
            )
        case ("GET", "/_admin/endpoints/GET/users"): return (200, #"{"currentCallCount":3}"#)
        case ("GET", "/_admin/scenarios"): return (200, #"[{"name":"empty","overrides":{"GET /users":"empty"}}]"#)
        case ("POST", "/_admin/sessions"): return (200, #"{"id":"s-1"}"#)
        case ("GET", "/_admin/sessions"):
            return (200, #"[{"id":"s-1","createdAt":0,"lastUsedAt":0,"expiresAt":1800}]"#)
        case ("POST", "/_admin/reload"): return (200, #"{"endpoints":2,"scenarios":0,"sessions":1,"warnings":[]}"#)
        case (_, let path) where path.contains("rejected"): return (404, "missing")
        default: return (200, "{}")
        }
    }

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
