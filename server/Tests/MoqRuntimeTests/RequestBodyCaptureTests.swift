import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqCore
@testable import MoqRuntime

struct RequestBodyCaptureTests {
    private func store() async -> InMemoryMockStore {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .post, path: "/ai"))
        return store
    }

    @Test("Bodies are not recorded unless capture is enabled")
    func offByDefault() async throws {
        let store = await store()
        let app = try await buildApp(store: store)
        try await app.testing().test(.POST, "/ai", body: ByteBuffer(string: #"{"action":"translate"}"#)) { _ async in }
        #expect(await store.recentRequests().first?.requestBody == nil)
        try await app.asyncShutdown()
    }

    @Test("Captured bodies are recorded per session, and truncated at the limit")
    func capturesAndTruncates() async throws {
        let store = await store()
        let app = try await buildApp(store: store, requestBodyCaptureLimit: 16)
        let id = try await store.createRuntimeSession()
        try await app.testing().test(
            .POST, "/ai", headers: ["X-Mock-Session": id],
            body: ByteBuffer(string: #"{"action":"translate","language":"fr"}"#)
        ) { _ async in }
        try await app.testing().test(.POST, "/missing", body: ByteBuffer(string: "short")) { _ async in }

        let sessionRow = try #require(await store.runtimeSession(id)?.recentRequests().first)
        #expect(sessionRow.requestBody == CapturedBody(value: #"{"action":"trans"#, encoding: .utf8, size: 38, truncated: true))
        #expect(await store.recentRequests().first?.requestBody?.value == "short")
        try await app.asyncShutdown()
    }

    @Test("A cut inside a UTF-8 character backs off; binary bodies are base64")
    func encodingRules() {
        let accented = Data("aé".utf8)  // 3 bytes: 61 C3 A9
        #expect(CapturedBody.capture(accented, limit: 2) == CapturedBody(value: "a", encoding: .utf8, size: 3, truncated: true))
        let binary = Data([0xFF, 0xFE, 0x00, 0x01])
        #expect(CapturedBody.capture(binary, limit: 10) == CapturedBody(value: "//4AAQ==", encoding: .base64, size: 4, truncated: false))
    }
}
