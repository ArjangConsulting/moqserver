import Foundation

/// Optional deterministic byte streaming for response fixtures, including transport failure.
public struct ResponseStream: Codable, Equatable, Sendable {
    public let chunkBytes: Int
    public let intervalMs: Int
    public let disconnectAfterBytes: Int?

    public init(chunkBytes: Int, intervalMs: Int = 0, disconnectAfterBytes: Int? = nil) {
        self.chunkBytes = chunkBytes
        self.intervalMs = intervalMs
        self.disconnectAfterBytes = disconnectAfterBytes
    }

    enum CodingKeys: String, CodingKey {
        case chunkBytes = "chunk_bytes"
        case intervalMs = "interval_ms"
        case disconnectAfterBytes = "disconnect_after_bytes"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            chunkBytes: try values.decode(Int.self, forKey: .chunkBytes),
            intervalMs: try values.decodeIfPresent(Int.self, forKey: .intervalMs) ?? 0,
            disconnectAfterBytes: try values.decodeIfPresent(Int.self, forKey: .disconnectAfterBytes)
        )
        try validate()
    }

    public func validate() throws {
        guard chunkBytes > 0, (0...60_000).contains(intervalMs),
            disconnectAfterBytes.map({ $0 >= 0 }) ?? true
        else { throw Failure.invalidConfiguration }
    }

    public enum Failure: Error, Equatable { case invalidConfiguration, simulatedDisconnect }

    /// Waits only between writes; cancellation and writer failures stop delivery immediately.
    public func deliver(
        _ body: Data,
        write: (Data) async throws -> Void,
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) async throws {
        try validate()
        let end = min(body.count, disconnectAfterBytes ?? body.count)
        var offset = 0
        while offset < end {
            try Task.checkCancellation()
            if offset > 0, intervalMs > 0 { try await sleep(.milliseconds(intervalMs)) }
            try Task.checkCancellation()
            let next = offset + min(chunkBytes, end - offset)
            let startIndex = body.index(body.startIndex, offsetBy: offset)
            let endIndex = body.index(body.startIndex, offsetBy: next)
            try await write(Data(body[startIndex..<endIndex]))
            offset = next
        }
        if let disconnectAfterBytes, disconnectAfterBytes <= body.count {
            throw Failure.simulatedDisconnect
        }
    }
}
