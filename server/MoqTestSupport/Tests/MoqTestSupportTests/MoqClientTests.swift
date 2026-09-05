import Foundation
import XCTest

@testable import MoqTestSupport

final class MoqClientTests: XCTestCase {
    private func client(timeout: TimeInterval = 1) -> MoqClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return MoqClient(
            baseURL: URL(string: "http://localhost:9999")!, auth: .bearer("test-token"),
            session: URLSession(configuration: config), timeout: timeout)
    }

    func testRejectionThrowsTypedError() {
        XCTAssertThrowsError(try client().selectVariant("error", for: "GET", path: "/rejected")) { error in
            XCTAssertEqual(error as? MoqControlError, .rejected(status: 401, body: "unauthorized"))
        }
    }

    func testAuthenticationAndAtomicReset() throws {
        try client().resetAll(for: "GET", path: "/users/")
    }

    func testTimeoutCancelsTask() {
        let cancelled = expectation(description: "request cancelled")
        StubProtocol.cancellation = cancelled
        defer { StubProtocol.cancellation = nil }
        XCTAssertThrowsError(try client(timeout: 0.02).selectVariant("error", for: "GET", path: "/timeout"))
        wait(for: [cancelled], timeout: 1)
    }

    func testReadinessRespectsDeadline() {
        let start = ProcessInfo.processInfo.systemUptime
        XCTAssertFalse(client().waitUntilReady(timeout: 0.05))
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - start, 0.5)
    }
}

private final class StubProtocol: URLProtocol {
    static var cancellation: XCTestExpectation?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        if url.path.contains("timeout") || url.path == "/_admin/endpoints" { return }
        let rejected = url.path.contains("rejected")
        if !rejected {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            XCTAssertEqual(request.httpMethod, "DELETE")
            XCTAssertEqual(url.path, "/_admin/endpoints/GET/users/state")
        }
        let response = HTTPURLResponse(url: url, statusCode: rejected ? 401 : 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((rejected ? "unauthorized" : "{}").utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        if request.url?.path.contains("timeout") == true { Self.cancellation?.fulfill() }
    }
}
