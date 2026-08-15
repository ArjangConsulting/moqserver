import Logging
import MCP
import MoqMCP

@main
struct MoqMCPRunEntry {
    static func main() async throws {
        // Nothing but JSON-RPC may reach stdout — StdioTransport owns stdout for protocol
        // traffic, so the process-wide logging bootstrap routes everywhere else (stderr). A
        // stray `print` in any dependency would corrupt every client's stdio session, not just
        // log a warning.
        LoggingSystem.bootstrap { label in
            StreamLogHandler.standardError(label: label)
        }

        let server = await makeMoqMCPServer()
        try await server.start(transport: StdioTransport())
        await server.waitUntilCompleted()
    }
}
