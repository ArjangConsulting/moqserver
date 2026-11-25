import Foundation
import XCTest

@testable import MoqTestSupport

final class MoqClientAssertionsTests: XCTestCase {
    private func client(sessionID: String? = "s1") -> MoqClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [QueryStub.self]
        return MoqClient(
            baseURL: URL(string: "http://localhost:9999")!, session: URLSession(configuration: config),
            timeout: 1, sessionID: sessionID)
    }

    override func setUp() {
        super.setUp()
        QueryStub.reset()
    }

    func testAssertRequestSendsPredicatesAndReturnsMatchesOldestFirst() throws {
        QueryStub.responses = [QueryStub.passing]
        let calls = try client().assertRequest(
            endpoint: "GET /v1/me", times: 2,
            headers: [.scheme("Authorization", "Bearer"), .absent("Cookie"), .fingerprint("Authorization", of: "tok")],
            claims: ["sub": "u1"])

        XCTAssertEqual(calls.map(\.claims["iat"]), ["100", "200"])
        XCTAssertEqual(calls.first?.header("Authorization")?.fingerprint, "aaaaaaaaaaaaaaaa")
        let sent = try XCTUnwrap(QueryStub.bodies.first)
        XCTAssertEqual(sent["endpoint"] as? String, "GET /v1/me")
        XCTAssertEqual(sent["times"] as? Int, 2)
        XCTAssertEqual(sent["claims"] as? [String: String], ["sub": "u1"])
        let headers = try XCTUnwrap(sent["headers"] as? [[String: Any]])
        XCTAssertEqual(headers[0]["scheme"] as? String, "Bearer")
        XCTAssertEqual(headers[1]["present"] as? Bool, false)
        XCTAssertEqual(headers[2]["fingerprintOf"] as? String, "tok")
        XCTAssertEqual(QueryStub.sessionHeaders, ["s1"])
    }

    func testAssertRequestSendsQueryExpectationsAndDecodesCapturedQuery() throws {
        QueryStub.responses = [QueryStub.passing]
        let calls = try client().assertRequest(
            endpoint: "GET /v1/me",
            query: [.equals("status", "done"), .absent("page"), .present("tag"), .fingerprint("api_key", of: "k")])

        XCTAssertEqual(calls.first?.queryValues("tag"), ["a", "b"])
        XCTAssertEqual(calls.first?.queryValues("api_key"), [])
        XCTAssertEqual(calls.first?.requestQuery?[1].fingerprint, "bbbbbbbbbbbbbbbb")
        let query = try XCTUnwrap(QueryStub.bodies.first?["query"] as? [[String: Any]])
        XCTAssertEqual(query[0]["equals"] as? String, "done")
        XCTAssertEqual(query[1]["present"] as? Bool, false)
        XCTAssertEqual(query[2]["present"] as? Bool, true)
        XCTAssertEqual(query[3]["fingerprintOf"] as? String, "k")
        XCTAssertNil(QueryStub.bodies.first?["headers"])
    }

    func testAssertRequestWithoutEndpointChecksAnyRequestReachedTheSession() throws {
        QueryStub.responses = [QueryStub.failing, QueryStub.passing]
        XCTAssertEqual(try client().assertRequest(timeout: 2).count, 2)
        let sent = try XCTUnwrap(QueryStub.bodies.last)
        XCTAssertTrue(sent.isEmpty, "an unfiltered query has no fields: \(sent)")
        XCTAssertEqual(QueryStub.sessionHeaders, ["s1", "s1"])
    }

    func testFailedAssertionThrowsTheServerVerdict() {
        QueryStub.responses = [QueryStub.failing]
        XCTAssertThrowsError(try client().assertNoRejectedRequests()) { error in
            let failure = error as? MoqRequestAssertionError
            XCTAssertTrue(failure?.description.contains("missing_required_header") == true)
            XCTAssertEqual(failure?.result.matched.first?.rejection?.kind, "request_rule")
        }
        let sent = QueryStub.bodies.first
        XCTAssertEqual(sent?["rejected"] as? Bool, true)
        XCTAssertEqual(sent?["times"] as? Int, 0)
    }

    func testAssertRequestPollsUntilTimeout() throws {
        QueryStub.responses = [QueryStub.failing, QueryStub.passing]
        XCTAssertEqual(try client().assertRequest(endpoint: "GET /v1/me", timeout: 2).count, 2)
        XCTAssertEqual(QueryStub.bodies.count, 2)
    }

    func testSessionCheckSendsSessionlessScopeAndReturnsUnattributed() throws {
        QueryStub.responses = [QueryStub.passing]
        let unattributed = try client().assertAllRequestsCarrySession(claims: ["sub": "u1"])
        XCTAssertEqual(unattributed.map(\.path), ["/v1/anon"])
        let sent = QueryStub.bodies.first
        XCTAssertEqual(sent?["scope"] as? String, "sessionless")
        XCTAssertEqual(sent?["times"] as? Int, 0)
        XCTAssertNil(sent?["includeUnattributed"])
    }

    func testLimitedVariantSelectionSendsTimes() throws {
        QueryStub.responses = [Data(#"{"message":"ok"}"#.utf8)]
        try client().selectVariant("unauthorized", for: "GET", path: "/v1/me", times: 1)
        XCTAssertEqual(QueryStub.paths, ["/_admin/endpoints/GET/v1/me/variant"])
        XCTAssertEqual(QueryStub.bodies.first?["times"] as? Int, 1)
        XCTAssertEqual(QueryStub.bodies.first?["variant"] as? String, "unauthorized")
    }

    func testAsyncAssertRequestMatchesSyncBehaviour() async throws {
        QueryStub.responses = [QueryStub.passing]
        let calls = try await client().asyncClient.assertRequest(endpoint: "GET /v1/me", times: 2)
        XCTAssertEqual(calls.count, 2)
    }

    func testRecordDecodesHeadersAndRejection() throws {
        QueryStub.responses = [QueryStub.failing]
        let rejected = try client().rejectedRequests()
        XCTAssertTrue(rejected.first?.isRejected == true)
        XCTAssertFalse(rejected.first?.isUnmatched == true)
        XCTAssertEqual(rejected.first?.header("authorization")?.redacted, true)
    }
}

private final class QueryStub: URLProtocol {
    nonisolated(unsafe) static var responses: [Data] = []
    nonisolated(unsafe) static var bodies: [[String: Any]] = []
    nonisolated(unsafe) static var paths: [String] = []
    nonisolated(unsafe) static var sessionHeaders: [String?] = []
    private static let lock = NSLock()

    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        responses = []
        bodies = []
        paths = []
        sessionHeaders = []
    }

    static let passing = Data(
        """
        {"ok":true,"count":2,"expected":2,"message":"Found 2 request(s) to GET /v1/me, expected exactly 2.",
         "nearMisses":[],
         "unattributed":[{"id":"9","timestamp":9,"method":"GET","path":"/v1/anon","status":428,"reason":"missing session",
                          "rejection":{"kind":"session","code":"session_required","message":"Missing X-Mock-Session header"}}],
         "matched":[
          {"id":"1","timestamp":1,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":200,"reason":"declared default",
           "addons":{"jwt-claims":{"sub":"u1","iat":"100"}},
           "requestHeaders":[{"name":"authorization","truncated":false,"redacted":true,"scheme":"Bearer","fingerprint":"aaaaaaaaaaaaaaaa"}],
           "requestQuery":[{"name":"tag","value":"a","truncated":false,"redacted":false},
                           {"name":"api_key","truncated":false,"redacted":true,"fingerprint":"bbbbbbbbbbbbbbbb"},
                           {"name":"tag","value":"b","truncated":false,"redacted":false}]},
          {"id":"2","timestamp":2,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":200,"reason":"declared default",
           "addons":{"jwt-claims":{"sub":"u1","iat":"200"}}}
         ]}
        """.utf8)

    static let failing = Data(
        """
        {"ok":false,"count":1,"expected":0,"nearMisses":[],"unattributed":[],
         "message":"Expected exactly 0 request(s) matching [rejected], found 1.\\nMatched:\\n  GET /v1/me → 400 (request_rule missing_required_header: Header 'Authorization' is required)",
         "matched":[{"id":"1","timestamp":1,"method":"GET","path":"/v1/me","endpoint":"GET /v1/me","status":400,
           "reason":"missing_required_header",
           "requestHeaders":[{"name":"authorization","truncated":false,"redacted":true}],
           "rejection":{"kind":"request_rule","code":"missing_required_header","message":"Header 'Authorization' is required"}}]}
        """.utf8)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read) ?? Data()
        Self.lock.lock()
        Self.paths.append(url.path)
        Self.sessionHeaders.append(request.value(forHTTPHeaderField: "X-Mock-Session"))
        if let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any] { Self.bodies.append(object) }
        let data = Self.responses.isEmpty ? Data("{}".utf8) : Self.responses.removeFirst()
        Self.lock.unlock()
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

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
}

final class MoqServerInfoTests: XCTestCase {
    private func client(status: Int = 200, body: String) -> MoqClient {
        VersionStub.status = status
        VersionStub.body = Data(body.utf8)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [VersionStub.self]
        return MoqClient(
            baseURL: URL(string: "http://localhost:9999")!, session: URLSession(configuration: config), timeout: 1)
    }

    func testServerInfoDecodes() throws {
        let info = try client(body: #"{"version":"1.2.3","commit":"abc","capabilities":["request-query"]}"#)
            .serverInfo()
        XCTAssertEqual(info.version, "1.2.3")
        XCTAssertTrue(info.supports("request-query"))
        XCTAssertEqual(VersionStub.path, "/_admin/version")
    }

    func testRequireCapabilitiesListsWhatIsMissing() {
        let moq = client(body: #"{"version":null,"commit":null,"capabilities":["request-query"]}"#)
        XCTAssertNoThrow(try moq.requireCapabilities("request-query"))
        XCTAssertThrowsError(try moq.requireCapabilities("request-query", "limited-overrides")) { error in
            XCTAssertEqual((error as? MoqMissingCapabilitiesError)?.missing, ["limited-overrides"])
        }
    }

    func testRequireCapabilitiesTreatsAMissingRouteAsAnOlderServer() async {
        let moq = client(status: 404, body: "{}")
        do {
            try await moq.asyncClient.requireCapabilities("request-query")
            XCTFail("expected a missing capability")
        } catch let error as MoqMissingCapabilitiesError {
            XCTAssertNil(error.server)
            XCTAssertTrue(error.description.contains("without /_admin/version"))
        } catch {
            XCTFail("unexpected \(error)")
        }
    }
}

private final class VersionStub: URLProtocol {
    nonisolated(unsafe) static var status = 200
    nonisolated(unsafe) static var body = Data()
    nonisolated(unsafe) static var path: String?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        Self.path = url.path
        let response = HTTPURLResponse(url: url, statusCode: Self.status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
