import Foundation
import Logging
import MoqRuntime

private let logger = Logger(label: "moqserver.cli.ProjectWatcher")

/// Polls a `.moqproj` directory and reloads the server when any file in it changes (`serve --watch`).
/// Polling keeps it portable across macOS and Linux; one stat pass per second over a bundle is cheap.
struct ProjectWatcher: Sendable {
    let path: String
    let reloader: MockReloader
    var interval: Duration = .seconds(1)

    func start() -> Task<Void, Never> {
        logger.info("Watching project for changes", metadata: ["path": "\(path)"])
        print("Watching \(path) for changes")
        return Task.detached(priority: .utility) {
            var previous = Self.fingerprint(of: path)
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                let current = Self.fingerprint(of: path)
                guard current != previous else { continue }
                previous = current
                do {
                    let summary = try await reloader.reload()
                    print("Reloaded project: \(summary.endpoints) endpoint(s)")
                    for warning in summary.warnings { print("WARNING: reload \(warning)") }
                } catch {
                    print("\(error)")
                }
            }
        }
    }

    /// Every file's relative path, size and modification time. Any edit, add, remove or rename changes it.
    static func fingerprint(of path: String) -> [String: String] {
        let root = URL(fileURLWithPath: (path as NSString).expandingTildeInPath)
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey]
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys) else {
            return [:]
        }
        var result: [String: String] = [:]
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else {
                continue
            }
            let modified = values.contentModificationDate?.timeIntervalSince1970 ?? 0
            result[url.path] = "\(values.fileSize ?? -1):\(modified)"
        }
        return result
    }
}
