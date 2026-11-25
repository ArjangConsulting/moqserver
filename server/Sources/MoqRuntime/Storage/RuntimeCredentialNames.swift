import Foundation
import MoqCore

/// The credential names a running server redacts, shared by the mock and admin handlers. A reload
/// replaces them when the bundle's `redact:` section or declared credential headers change.
public final class RuntimeCredentialNames: @unchecked Sendable {
    private let lock = NSLock()
    private var names: CredentialNames

    public init(_ names: CredentialNames = .builtIn) {
        self.names = names
    }

    public var current: CredentialNames {
        lock.lock()
        defer { lock.unlock() }
        return names
    }

    func replace(with names: CredentialNames) {
        lock.lock()
        defer { lock.unlock() }
        self.names = names
    }
}
