import ArgumentParser
import Foundation
import MoqParsing

/// `moqserver validate-spec` — validates an OpenAPI spec for compliance and mock-readiness.
public struct ValidateSpecCommand: ParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "validate-spec",
        abstract: "Validate an OpenAPI spec for compliance and mock-readiness"
    )

    @ArgumentParser.Option(name: .long, help: "Path or URL to an OpenAPI spec (YAML or JSON)")
    var spec: String

    public init() {}

    public mutating func run() throws {
        let specLoader = SpecLoader()
        let validator = OpenAPISpecValidator()

        let data = try specLoader.loadData(from: spec)
        let diagnostics = validator.validate(data: data)

        let errors = diagnostics.filter { $0.severity == .error }
        let warnings = diagnostics.filter { $0.severity == .warning }

        for diagnostic in diagnostics {
            print(diagnostic)
        }

        if errors.isEmpty && warnings.isEmpty {
            print("Spec is valid with no issues.")
        } else {
            print("\n\(errors.count) error(s), \(warnings.count) warning(s)")
        }

        if !errors.isEmpty {
            throw ExitCode.failure
        }
    }
}
