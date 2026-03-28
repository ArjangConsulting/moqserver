import Foundation

/// Protocol for loading spec data from file paths or URLs.
public protocol SpecLoading: Sendable {
    func loadData(from source: String) throws -> Data
}
