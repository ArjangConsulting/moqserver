/// Network simulation configuration in the .moqproj format.
public struct NetworkBehavior: Codable, Sendable, Equatable {
    public let latencyMs: Int?
    public let jitterMs: Int?
    public let packetLossPercent: Double?

    public init(latencyMs: Int? = nil, jitterMs: Int? = nil, packetLossPercent: Double? = nil) {
        self.latencyMs = latencyMs
        self.jitterMs = jitterMs
        self.packetLossPercent = packetLossPercent
    }

    enum CodingKeys: String, CodingKey {
        case latencyMs = "latency_ms"
        case jitterMs = "jitter_ms"
        case packetLossPercent = "packet_loss_percent"
    }
}
