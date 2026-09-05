import ArgumentParser
import Foundation
import Logging

extension MoqAuthorCommand {
    public static func runMain() async {
        LoggingSystem.bootstrap { _ in SwiftLogNoOpLogHandler() }
        var command: any AsyncParsableCommand
        do {
            guard let parsed = try parseAsRoot() as? any AsyncParsableCommand else {
                exit(withError: nil)
            }
            command = parsed
        } catch {
            if exitCode(for: error) == .success { exit(withError: error) }
            let payload = ["code": "E_INVALID_ARGUMENTS", "message": message(for: error)]
            if let data = try? JSONEncoder().encode(payload) {
                FileHandle.standardError.write(data)
                FileHandle.standardError.write(Data("\n".utf8))
            }
            exit(withError: ExitCode.validationFailure)
        }
        do {
            try await command.run()
        } catch {
            exit(withError: error)
        }
    }
}
