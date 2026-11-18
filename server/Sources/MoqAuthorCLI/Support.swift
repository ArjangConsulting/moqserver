import ArgumentParser
import Foundation
import MoqService

/// Reads a structured JSON argument from a file, or from stdin when `path` is `"-"` — the same
/// convention `jq`, `kubectl`, and most other JSON-taking CLIs use.
func readJSONInput(_ path: String) throws -> Data {
    if path == "-" {
        return FileHandle.standardInput.readDataToEndOfFile()
    }
    return try Data(contentsOf: URL(fileURLWithPath: path))
}

/// Decodes a structured argument from `path` into `T` — the same `Decodable` payload types
/// `moq-mcp` and `moq-format` decode tool/JSON-RPC arguments into, so a script authoring the same
/// endpoint/variant shape gets identical field names and validation, whichever entry point it uses.
func decodeJSON<T: Decodable>(_ type: T.Type, from path: String) throws -> T {
    let data = try readJSONInput(path)
    return try JSONDecoder().decode(T.self, from: data)
}

/// Prints a value as pretty, sorted-key JSON on stdout — the parseable half of this CLI's
/// contract with a calling script. Human-oriented progress/errors go to stderr instead (see
/// `printError`), so stdout is safe to pipe into `jq` or capture as one blob.
func printJSON<T: Encodable>(_ value: T) throws {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    let data = try encoder.encode(value)
    print(String(decoding: data, as: UTF8.self))
}

/// Every failure funnels through the same `moqServiceErrorCode` mapping the MCP and JSON-RPC
/// adapters use, so a script sees the same `{code, message}` vocabulary regardless of which
/// moqserver entry point it drives — one error catalog, three transports.
func printError(_ error: Error) {
    let (code, message) = (error as? AuthorCLIError).map { ($0.code, $0.description) } ?? moqServiceErrorCode(error)
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    let payload = ["code": code, "message": message]
    let data = (try? encoder.encode(payload)) ?? Data("{\"code\":\"\(code)\"}".utf8)
    FileHandle.standardError.write(data)
    FileHandle.standardError.write(Data("\n".utf8))
}

/// Opens `path` in a fresh session and hands back the service + handle. Every subcommand is a
/// one-shot process: open, read or apply exactly one mutation with `autosave: true`, exit. There
/// is no session to reuse across invocations, so nothing here needs `force` — a brand-new
/// session's dirty flag always starts `false`. Commands that save go through
/// `openProjectForEditing` instead.
func openExistingProject(_ path: String, allowNetworkImport: Bool) async throws -> (MoqService, String) {
    let service = MoqService(allowNetworkImport: allowNetworkImport)
    let handle = await service.openSession()
    _ = try await service.openProject(handle: handle, path: path, force: false)
    return (service, handle)
}

/// Failures that only exist at the command line, outside `MoqService`'s shared error catalog.
enum AuthorCLIError: Error, CustomStringConvertible {
    case projectIsWorkingDirectory(String)

    var code: String {
        switch self {
        case .projectIsWorkingDirectory: return "E_PROJECT_IS_WORKING_DIRECTORY"
        }
    }

    var description: String {
        switch self {
        case .projectIsWorkingDirectory(let path):
            return "The working directory is inside the bundle at \(path). A save replaces the bundle "
                + "directory, which would leave this shell in a directory that no longer exists. "
                + "Run the command from outside the bundle and pass its path."
        }
    }
}

/// Opens `path` for a command that will save. Saving swaps the whole bundle directory for a
/// freshly staged one, so a caller whose working directory is the bundle, or anything under it,
/// would be left standing in the removed copy; it is refused before anything is touched.
func openProjectForEditing(_ path: String, allowNetworkImport: Bool) async throws -> (MoqService, String) {
    try refuseEditing(bundleAt: path, from: FileManager.default.currentDirectoryPath)
    return try await openExistingProject(path, allowNetworkImport: allowNetworkImport)
}

/// `path` is resolved against `workingDirectory`, the way the process itself would resolve it.
func refuseEditing(bundleAt path: String, from workingDirectory: String) throws {
    let base = URL(fileURLWithPath: workingDirectory, isDirectory: true)
    let bundle = URL(fileURLWithPath: path, relativeTo: base).standardizedFileURL.resolvingSymlinksInPath().path
    let current = base.standardizedFileURL.resolvingSymlinksInPath().path
    if current == bundle || current.hasPrefix(bundle + "/") {
        throw AuthorCLIError.projectIsWorkingDirectory(bundle)
    }
}

/// Gated the same way as `moq-mcp` (`MOQ_MCP_ALLOW_NETWORK`) and `moq-format`
/// (`MOQ_FORMAT_ALLOW_NETWORK`) — off by default; a local file source for `import openapi` always
/// works regardless.
var allowNetworkImportFromEnvironment: Bool {
    ProcessInfo.processInfo.environment["MOQ_AUTHOR_ALLOW_NETWORK"] == "1"
}

func authorImportOptions(_ path: String?) throws -> ImportInputCommon {
    let options = try path.map { try decodeJSON(ImportInputCommon.self, from: $0) } ?? ImportInputCommon()
    return ImportInputCommon(
        acceptPaths: options.acceptPaths, updateDetails: options.updateDetails ?? false,
        replaceExistingBodies: options.replaceExistingBodies ?? false)
}
