import Foundation

/// Protocol for loading server configuration from files.
public protocol ConfigLoading: Sendable {
    func load(from path: String) throws -> ServerConfiguring
}

/// Protocol for server configuration values used by the runtime.
public protocol ServerConfiguring: Sendable {
    var variantOverrides: [String: String]? { get }
    var globalDelay: TimeInterval? { get }
    var delayOverrides: [String: TimeInterval]? { get }
    var mocksDirectory: String? { get }
    var overridesPersistencePath: String? { get }

    func variantOverride(for endpointKey: String) -> String?
    func effectiveDelay(for endpointKey: String) -> TimeInterval?
}
