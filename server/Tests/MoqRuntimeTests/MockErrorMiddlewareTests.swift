import Foundation
import Testing
import Vapor
import VaporTesting

@testable import MoqCore
@testable import MoqRuntime

@Suite("ErrorResponse encodes structured error payloads")
struct ErrorResponseTests {

    @Test("ErrorResponse encodes to valid JSON")
    func errorResponseJson() throws {
        let response = ErrorResponse(error: "Not found", code: "not_found")
        let data = response.jsonData()
        let decoded = try JSONDecoder().decode(ErrorResponse.self, from: data)
        #expect(decoded.error == "Not found")
        #expect(decoded.code == "not_found")
    }

    @Test("ErrorResponse with all common error codes")
    func errorResponseAllCodes() throws {
        let codes = [
            "bad_request", "unauthorized", "forbidden", "not_found", "unsupported_media_type", "internal_error",
        ]
        for code in codes {
            let response = ErrorResponse(error: "test: \(code)", code: code)
            let data = response.jsonData()
            let decoded = try JSONDecoder().decode(ErrorResponse.self, from: data)
            #expect(decoded.code == code)
            #expect(decoded.error == "test: \(code)")
        }
    }

    @Test("ErrorResponse JSON contains expected keys")
    func jsonShape() throws {
        let response = ErrorResponse(error: "something", code: "bad_request")
        let data = response.jsonData()
        let dict = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(dict?["error"] as? String == "something")
        #expect(dict?["code"] as? String == "bad_request")
    }

    @Test("ErrorResponse equatable works correctly")
    func equatable() {
        let a = ErrorResponse(error: "test", code: "not_found")
        let b = ErrorResponse(error: "test", code: "not_found")
        let c = ErrorResponse(error: "other", code: "not_found")
        #expect(a == b)
        #expect(a != c)
    }
}

@Suite("MockErrorMiddleware cancellation")
struct MockErrorMiddlewareCancellationTests {
    private struct CancellingResponder: AsyncResponder {
        func respond(to request: Request) async throws -> Response {
            throw CancellationError()
        }
    }

    @Test("Cancellation propagates instead of becoming an HTTP 500")
    func cancellationPropagates() async throws {
        let app = try await Application.make(.testing)
        let request = Request(
            application: app,
            method: .GET,
            url: "/cancelled",
            peerCertificateChain: nil,
            on: app.eventLoopGroup.any()
        )

        await #expect(throws: CancellationError.self) {
            try await MockErrorMiddleware().respond(to: request, chainingTo: CancellingResponder())
        }
        try await app.asyncShutdown()
    }
}

@Suite("MockErrorMiddleware error codes")
struct MockErrorMiddlewareCodeTests {
    @Test(
        "Every abort status maps to a specific code",
        arguments: [
            (HTTPResponseStatus.conflict, "conflict"),
            (.payloadTooLarge, "payload_too_large"),
            (.notImplemented, "not_implemented"),
            (.methodNotAllowed, "method_not_allowed"),
            (.notAcceptable, "not_acceptable"),
            (.gone, "gone"),
            (.internalServerError, "internal_error"),
            (.unsupportedMediaType, "unsupported_media_type"),
        ])
    func statusCodes(status: HTTPResponseStatus, code: String) {
        #expect(MockErrorMiddleware.statusToCode(status) == code)
    }

    @Test("A 409 abort reports conflict, not internal_error")
    func conflictAbortEncodesCode() async throws {
        let app = try await Application.make(.testing)
        app.middleware = Middlewares()
        app.middleware.use(MockErrorMiddleware())
        app.get("full") { _ async throws -> String in
            throw Abort(.conflict, reason: "Scenario or session capacity reached")
        }
        try await app.testing().test(.GET, "/full") { res async in
            #expect(res.status == .conflict)
            let body = try? JSONDecoder().decode(ErrorResponse.self, from: Data(buffer: res.body))
            #expect(body?.code == "conflict")
        }
        try await app.asyncShutdown()
    }
}
