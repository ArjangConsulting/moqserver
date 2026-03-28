import Foundation

/// Protocol for loading mock response files from a directory.
public protocol MockFileLoading: Sendable {
    func load(from directory: String) throws -> [Endpoint]
}
