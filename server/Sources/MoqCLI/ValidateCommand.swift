import ArgumentParser
import Foundation
import MoqCore
import MoqFormat

/// `moqserver validate` — validates a .moqproj directory for correctness.
public struct ValidateCommand: ParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "validate",
        abstract: "Validate a .moqproj project directory"
    )

    @ArgumentParser.Option(name: .long, help: "Path to a .moqproj project directory")
    var project: String

    public init() {}

    public mutating func run() throws {
        let loader = ProjectLoader()
        let validator = ProjectValidator()

        let moqProject = try loader.load(from: project)
        let diagnostics = validator.validate(moqProject)

        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }

        for diagnostic in diagnostics {
            print(diagnostic)
        }

        if errors.isEmpty && warnings.isEmpty {
            print("Project \"\(moqProject.manifest.name)\" is valid.")
        } else {
            print("\n\(errors.count) error(s), \(warnings.count) warning(s)")
        }

        if !errors.isEmpty {
            throw ExitCode.failure
        }
    }
}
