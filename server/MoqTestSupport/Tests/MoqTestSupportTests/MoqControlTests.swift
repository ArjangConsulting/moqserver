import XCTest

@testable import MoqTestSupport

/// Pure checks on URL construction — the parts of `MoqControl` that don't need a live server.
/// Live behavior (selecting a variant, resetting a call count) is exercised by consuming apps'
/// own UI test suites against a real `moqserver` instance, not here.
final class MoqControlTests: XCTestCase {

    func testAdminURLNormalizesLeadingAndTrailingSlashes() {
        MoqControl.baseURL = URL(string: "http://127.0.0.1:9999")!
        let url = MoqControl.adminURL(method: "get", path: "/v1/videos/1440/", subresource: "variant")
        XCTAssertEqual(url.absoluteString, "http://127.0.0.1:9999/_admin/endpoints/GET/v1/videos/1440/variant")
    }

    func testAdminURLAcceptsPathWithNoLeadingOrTrailingSlash() {
        MoqControl.baseURL = URL(string: "http://127.0.0.1:9999")!
        let url = MoqControl.adminURL(method: "POST", path: "users", subresource: "call-count")
        XCTAssertEqual(url.absoluteString, "http://127.0.0.1:9999/_admin/endpoints/POST/users/call-count")
    }

    func testMethodIsUppercased() {
        MoqControl.baseURL = URL(string: "http://127.0.0.1:9999")!
        let url = MoqControl.adminURL(method: "get", path: "/users", subresource: "variant")
        XCTAssertTrue(url.absoluteString.contains("/endpoints/GET/"))
    }
}
