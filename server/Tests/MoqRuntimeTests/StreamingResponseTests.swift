import Foundation
import Testing
import Vapor
import VaporTesting

@testable import MoqCore
@testable import MoqRuntime

struct StreamingResponseTests {
    @Test func surfacesASimulatedDisconnectAsATransportFailure() async throws {
        let store = InMemoryMockStore()
        await store.register(makeTestEndpoint(method: .get, path: "/events", variants: [
            ResponseVariant(name: "disconnect", body: Data("partial answer".utf8),
                            stream: ResponseStream(chunkBytes: 2, disconnectAfterBytes: 4))
        ]))
        let app = try await buildApp(store: store)
        await #expect(throws: ResponseStream.Failure.simulatedDisconnect) {
            _ = try await app.performTest(request: .init(
                method: .GET, url: .init(path: "/events"), headers: [:], body: ByteBuffer()
            ))
        }
        try await app.asyncShutdown()
    }

    @Test func servesFixtureBytesThroughTheStreamingWriter() async throws {
        let store = InMemoryMockStore()
        let fixture = "event: update\ndata: café\n\n"
        await store.register(makeTestEndpoint(method: .get, path: "/events", variants: [
            ResponseVariant(name: "stream", headers: [("Content-Type", "text/event-stream")],
                            body: Data(fixture.utf8), stream: ResponseStream(chunkBytes: 1))
        ]))
        let app = try await buildApp(store: store)
        do {
            try await app.testing().test(.GET, "/events") { response async in
                #expect(response.status == .ok)
                #expect(response.body.string == fixture)
                #expect(response.headers.first(name: .contentType) == "text/event-stream")
            }
        } catch {
            try await app.asyncShutdown()
            throw error
        }
        try await app.asyncShutdown()
    }
}
