import ArgumentParser
import Foundation
import Logging

extension MoqAuthorCommand {
    public static func runMain() async {
        LoggingSystem.bootstrap { _ in SwiftLogNoOpLogHandler() }
        var command: any ParsableCommand
        do {
            command = try parseAsRoot()
        } catch {
            if exitCode(for: error) == .success {
                // --help and --version arrive as a success-coded error whose message is the text
                // to show; it goes to stdout so `moq-author --help | less` works.
                print(fullMessage(for: error))
                Foundation.exit(ExitCode.success.rawValue)
            }
            let payload = ["code": "E_INVALID_ARGUMENTS", "message": message(for: error)]
            if let data = try? JSONEncoder().encode(payload) {
                FileHandle.standardError.write(data)
                FileHandle.standardError.write(Data("\n".utf8))
            }
            exit(withError: ExitCode.validationFailure)
        }
        do {
            // `--help` parses to ArgumentParser's own synchronous help command, not to one of
            // the async subcommands, so both kinds have to run.
            if var asyncCommand = command as? any AsyncParsableCommand {
                try await asyncCommand.run()
            } else {
                try command.run()
            }
        } catch {
            exit(withError: error)
        }
    }
}
