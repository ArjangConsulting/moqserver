import Foundation
import XCTest

@testable import MoqTestSupport

final class MoqClientRequestsTests: XCTestCase {
    private func client(sessionID: String? = "s1") -> MoqClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [HistoryStub.self]
        return MoqClient(
            baseURL: URL(string: "http://localhost:9999")!, session: URLSession(configuration: config),
            timeout: 1, sessionID: sessionID)
    }

    func testRequestsDecodeHistoryAndSendSessionHeader() throws {
        let records = try client().requests()
        XCTAssertEqual(records.count, 3)
        XCTAssertEqual(records[0].addons, ["jwt-claims": ["sub": "alice"]])
        XCTAssertEqual(HistoryStub.lastSessionHeader, "s1")
    }

    func testUnmatchedRequestsAreOldestFirst() throws {
        XCTAssertEqual(try client().unmatchedRequests().map(\.path), ["/balance", "/missing"])
    }

    func testAssertNoUnmatchedRequestsThrowsWithPaths() {
        XCTAssertThrowsError(try client().assertNoUnmatchedRequests()) { error in
            let unmatched = error as? MoqUnmatchedRequestsError
            XCTAssertEqual(unmatched?.requests.map(\.path), ["/balance", "/missing"])
            XCTAssertTrue(unmatched?.description.contains("GET /balance") == true)
        }
    }
}

private final class HistoryStub: URLProtocol {
    nonisolated(unsafe) static var lastSessionHeader: String?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        Self.lastSessionHeader = request.value(forHTTPHeaderField: "X-Mock-Session")
        XCTAssertEqual(url.path, "/_admin/requests")
        let body = """
            [
              {"id":"3","timestamp":3,"method":"GET","path":"/missing","status":404,"reason":"endpoint not found",
               "addons":{"jwt-claims":{"sub":"alice"}}},
              {"id":"2","timestamp":2,"method":"GET","path":"/users","endpoint":"GET /users","status":200,
               "variant":"success","reason":"declared default","callNumber":1},
              {"id":"1","timestamp":1,"method":"GET","path":"/balance","status":404,"reason":"endpoint not found"}
            ]
            """
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
