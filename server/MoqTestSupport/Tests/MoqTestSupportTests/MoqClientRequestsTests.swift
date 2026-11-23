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
        XCTAssertEqual(records[1].requestBody?.jsonObject?["action"] as? String, "summarize")
        XCTAssertNil(records[0].requestBody)
    }

    func testUnmatchedRequestsAskTheServerAndAreOldestFirst() throws {
        XCTAssertEqual(try client().unmatchedRequests().map(\.path), ["/cart", "/missing"])
        XCTAssertEqual(HistoryStub.lastQuery?["unmatched"] as? Bool, true)
    }

    func testAssertNoUnmatchedRequestsThrowsWithPaths() {
        XCTAssertThrowsError(try client().assertNoUnmatchedRequests()) { error in
            let unmatched = error as? MoqUnmatchedRequestsError
            XCTAssertEqual(unmatched?.requests.map(\.path), ["/cart", "/missing"])
            XCTAssertTrue(unmatched?.description.contains("GET /cart") == true)
        }
    }
}

private final class HistoryStub: URLProtocol {
    nonisolated(unsafe) static var lastSessionHeader: String?
    nonisolated(unsafe) static var lastQuery: [String: Any]?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        Self.lastSessionHeader = request.value(forHTTPHeaderField: "X-Mock-Session")
        if url.path == "/_admin/requests/query" {
            let sent = request.httpBody ?? request.httpBodyStream.map(Self.read) ?? Data()
            Self.lastQuery = try? JSONSerialization.jsonObject(with: sent) as? [String: Any]
            let result = """
                {"ok":true,"count":2,"message":"Found 2","nearMisses":[],"unattributed":[],"matched":[
                  {"id":"1","timestamp":1,"method":"GET","path":"/cart","status":404,"reason":"endpoint not found"},
                  {"id":"3","timestamp":3,"method":"GET","path":"/missing","status":404,"reason":"endpoint not found"}]}
                """
            respond(url, Data(result.utf8))
            return
        }
        XCTAssertEqual(url.path, "/_admin/requests")
        let body = """
            [
              {"id":"3","timestamp":3,"method":"GET","path":"/missing","status":404,"reason":"endpoint not found",
               "addons":{"jwt-claims":{"sub":"alice"}}},
              {"id":"2","timestamp":2,"method":"POST","path":"/ai","endpoint":"POST /ai","status":200,
               "variant":"success","reason":"declared default","callNumber":1,
               "requestBody":{"value":"{\\"action\\":\\"summarize\\"}","encoding":"utf8","size":22,"truncated":false}},
              {"id":"1","timestamp":1,"method":"GET","path":"/cart","status":404,"reason":"endpoint not found"}
            ]
            """
        respond(url, Data(body.utf8))
    }

    private func respond(_ url: URL, _ data: Data) {
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }

    override func stopLoading() {}
}
