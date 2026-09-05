import Foundation

#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// A stable sibling inode coordinates independent stores and processes. Never unlink the lock
/// file: a waiter may still hold that inode while a new writer opens the replacement.
final class BundleLock {
    private let descriptor: Int32

    init(path: String) throws {
        let url = URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath()
        let lockPath = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).lock").path
        descriptor = open(lockPath, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0o600)
        guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            let code = errno
            close(descriptor)
            if code == EWOULDBLOCK || code == EAGAIN { throw ProjectStoreError.projectBusy }
            throw POSIXError(POSIXErrorCode(rawValue: code) ?? .EIO)
        }
    }

    deinit {
        flock(descriptor, LOCK_UN)
        close(descriptor)
    }
}
