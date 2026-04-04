import ArgumentParser
import Foundation
import Logging
import MoqCore
import MoqParsing
import MoqRuntime

private let logger = Logger(label: "moqserver.cli.InitCommand")

/// `moqserver init` — scaffolds a mocks directory from an OpenAPI spec.
public struct InitCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "init",
        abstract: "Scaffold a mocks directory from an OpenAPI spec or HAR file"
    )

    @ArgumentParser.Option(name: .long, help: "Path or URL to an OpenAPI spec or HAR file")
    var spec: String

    @ArgumentParser.Option(name: .long, help: "Input format: auto, openapi, or har")
    var format: String = "auto"

    @ArgumentParser.Option(name: .long, help: "Output directory for mock files")
    var output: String = "./mocks"

    public init() {}

    public mutating func run() async throws {
        logger.info("Scaffolding mocks", metadata: ["source": "\(spec)", "format": "\(format)", "output": "\(output)"])
        let specLoader = SpecLoader()
        let specData = try specLoader.loadData(from: spec)
        let inputFormat = resolveFormat(format)
        let parsedSpecLoader = ParsedSpecLoader()
        let parsedSpec = try parsedSpecLoader.parse(data: specData, source: spec, format: inputFormat)
        logger.debug("Parsed spec", metadata: ["endpoints": "\(parsedSpec.endpoints.count)"])

        if inputFormat == .har || parsedSpec.endpoints.contains(where: { endpoint in
            endpoint.responses.contains(where: { $0.requestMatch != nil })
        }) {
            do {
                let filesCreated = try MockFileScaffolder.scaffold(from: parsedSpec, output: output)
                logger.info("Scaffolded mock files via MockFileScaffolder", metadata: ["count": "\(filesCreated)"])
                print("Scaffolded \(filesCreated) mock file(s) in \((output as NSString).expandingTildeInPath)")
            } catch {
                logger.error("Failed to scaffold mock files", metadata: ["output": "\(output)", "error": "\(error.localizedDescription)"])
                throw ValidationError("Failed to scaffold mock files to \(output): \(error.localizedDescription)")
            }
            return
        }

        let outputPath = (output as NSString).expandingTildeInPath
        let fm = FileManager.default

        var filesCreated = 0

        for endpoint in parsedSpec.endpoints {
            let pathDir = endpoint.path
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

            let dirURL = URL(fileURLWithPath: outputPath)
                .appendingPathComponent(pathDir, isDirectory: true)

            try fm.createDirectory(at: dirURL, withIntermediateDirectories: true)

            for response in endpoint.responses {
                let filename: String
                if response.name == "default" {
                    filename = "\(endpoint.method.uppercased()).json"
                } else {
                    filename = "\(endpoint.method.uppercased()).\(response.name).json"
                }

                let fileURL = dirURL.appendingPathComponent(filename)

                let body = response.body ?? Data("{}".utf8)
                let prettyData: Data
                if let jsonObj = try? JSONSerialization.jsonObject(with: body),
                   let pretty = try? JSONSerialization.data(withJSONObject: jsonObj, options: [.prettyPrinted, .sortedKeys]) {
                    prettyData = pretty
                } else {
                    prettyData = body
                }

                try prettyData.write(to: fileURL)
                filesCreated += 1
                print("  Created \(fileURL.path)")
            }
        }

        logger.info("Scaffolding complete", metadata: ["filesCreated": "\(filesCreated)", "outputPath": "\(outputPath)"])
        print("Scaffolded \(filesCreated) mock file(s) in \(outputPath)")
    }

    private func resolveFormat(_ format: String) -> SpecInputFormat {
        switch format.lowercased() {
        case "openapi": return .openapi
        case "har": return .har
        default: return .auto
        }
    }
}
