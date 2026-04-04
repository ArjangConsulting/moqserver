import ArgumentParser
import Foundation
import Logging
import MoqParsing

private let logger = Logger(label: "moqserver.cli.ValidateSpecCommand")

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
        logger.info("Validating spec", metadata: ["source": "\(spec)"])
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
            logger.info("Spec is valid with no issues")
            print("Spec is valid with no issues.")
        } else {
            logger.warning("Spec validation issues", metadata: ["errors": "\(errors.count)", "warnings": "\(warnings.count)"])
            print("\n\(errors.count) error(s), \(warnings.count) warning(s)")
        }

        if !errors.isEmpty {
            throw ExitCode.failure
        }
    }
}
