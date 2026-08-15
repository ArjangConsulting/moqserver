import Foundation
import Logging
import MoqService

/// `moq-format`: `MoqService` exposed as a long-lived stdio process, `Content-Length`-framed
/// JSON-RPC 2.0 (LSP-style framing — see `ContentLengthFraming`). A JVM client (Studio) drives
/// this instead of maintaining a second Kotlin implementation of `.moqproj` validation/writing.
///
/// Network import is off by default, matching `moq-mcp`'s default-closed posture; set
/// `MOQ_FORMAT_ALLOW_NETWORK=1` on this process to allow `import.openapi` to fetch a URL source.
/// Serializes writes to stdout — a plain `FileHandle` write is not itself atomic across
/// concurrently-dispatched responses.
private actor ResponseWriter {
    private let stdout = FileHandle.standardOutput

    func write(_ data: Data) {
        try? ContentLengthFraming.writeMessage(data, to: stdout)
    }
}

@main
struct MoqFormatServiceRunEntry {
    static func main() async throws {
        // Nothing but framed JSON-RPC may reach stdout — a stray `print` in any dependency would
        // corrupt every in-flight response, not just log a warning. Route logging to stderr.
        LoggingSystem.bootstrap { label in
            StreamLogHandler.standardError(label: label)
        }

        let service = MoqService(
            allowNetworkImport: ProcessInfo.processInfo.environment["MOQ_FORMAT_ALLOW_NETWORK"] == "1")
        let dispatcher = Dispatcher(service: service)

        let stdin = FileHandle.standardInput
        let writer = ResponseWriter()

        // Requests are dispatched concurrently — a slow import.har shouldn't block a concurrent
        // endpoint.get on a different session — but each write to stdout is serialized through
        // the writer actor so two responses can never interleave their bytes.
        try await withThrowingTaskGroup(of: Void.self) { group in
            while let requestData = try ContentLengthFraming.readMessage(from: stdin) {
                group.addTask {
                    let responseData = await dispatcher.handle(requestData)
                    await writer.write(responseData)
                }
            }
            try await group.waitForAll()
        }
    }
}
