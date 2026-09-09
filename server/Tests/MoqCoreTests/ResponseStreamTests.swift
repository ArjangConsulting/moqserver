import Foundation
import Testing

@testable import MoqCore

struct ResponseStreamTests {
    @Test func preservesBytesAcrossBoundariesAndWaitsBetweenChunks() async throws {
        let body = Data("skipα\n\ndata: ok\n\n".utf8).dropFirst(4)
        var chunks: [Data] = []
        var waits: [Duration] = []
        try await ResponseStream(chunkBytes: 1, intervalMs: 15).deliver(body,
            write: { chunks.append($0) }, sleep: { waits.append($0) })
        #expect(chunks.reduce(Data(), +) == body)
        #expect(waits == Array(repeating: .milliseconds(15), count: body.count - 1))
    }

    @Test func disconnectStopsAtTheRequestedByte() async throws {
        var received = Data()
        await #expect(throws: ResponseStream.Failure.simulatedDisconnect) {
            try await ResponseStream(chunkBytes: 4, disconnectAfterBytes: 5).deliver(Data("123456789".utf8)) {
                received.append($0)
            }
        }
        #expect(received == Data("12345".utf8))
    }

    @Test func projectVariantRetainsStreamingOptionsAcrossSerialization() throws {
        let stream = ResponseStream(chunkBytes: 3, intervalMs: 5, disconnectAfterBytes: 9)
        let variant = ProjectVariant(name: "stream", status: 200, stream: stream)
        let encoded = try JSONEncoder().encode(variant)
        let decoded = try JSONDecoder().decode(ProjectVariant.self, from: encoded)
        #expect(decoded.stream == stream)
    }

    @Test func rejectsInvalidConfigurationWhenDecoding() {
        #expect(throws: ResponseStream.Failure.invalidConfiguration) {
            try JSONDecoder().decode(ResponseStream.self, from: Data(#"{"chunk_bytes":0}"#.utf8))
        }
    }

    @Test func writerFailureStopsSubsequentChunks() async {
        var writes = 0
        await #expect(throws: CancellationError.self) {
            try await ResponseStream(chunkBytes: 1).deliver(Data("abc".utf8)) { _ in
                writes += 1
                throw CancellationError()
            }
        }
        #expect(writes == 1)
    }
}
