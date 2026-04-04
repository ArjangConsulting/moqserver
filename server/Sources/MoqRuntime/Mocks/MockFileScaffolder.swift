import Foundation

import Logging
import MoqCore

private let logger = Logger(label: "moqserver.runtime.MockFileScaffolder")

public enum MockFileScaffolder {
    private struct VariantMeta: Encodable {
        let statusCode: Int?
        let headers: [String: String]?
        let requestMatch: RequestMatch?
    }

    public static func scaffold(from parsedSpec: ParsedSpec, output: String) throws -> Int {
        logger.info("Scaffolding mock files to \(output)")
        let outputPath = (output as NSString).expandingTildeInPath
        let fm = FileManager.default
        var filesCreated = 0

        for endpoint in parsedSpec.endpoints {
            let pathDir = endpoint.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let dirURL = URL(fileURLWithPath: outputPath)
                .appendingPathComponent(pathDir, isDirectory: true)

            try fm.createDirectory(at: dirURL, withIntermediateDirectories: true)

            for response in endpoint.responses {
                let filename = response.name == "default"
                    ? "\(endpoint.method.uppercased()).json"
                    : "\(endpoint.method.uppercased()).\(response.name).json"

                let fileURL = dirURL.appendingPathComponent(filename)
                let body = response.body ?? Data("{}".utf8)
                let prettyData = prettyPrintedBody(from: body)
                try prettyData.write(to: fileURL)
                filesCreated += 1
                print("  Created \(fileURL.path)")

                if let meta = makeMeta(for: response) {
                    let metaURL = fileURL.deletingPathExtension().appendingPathExtension("meta.json")
                    let encoder = JSONEncoder()
                    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
                    try encoder.encode(meta).write(to: metaURL)
                    print("  Created \(metaURL.path)")
                }
            }
        }

        logger.info("Scaffolding complete: \(filesCreated) file(s) created")
        return filesCreated
    }

    private static func prettyPrintedBody(from body: Data) -> Data {
        if let jsonObj = try? JSONSerialization.jsonObject(with: body),
           let pretty = try? JSONSerialization.data(withJSONObject: jsonObj, options: [.prettyPrinted, .sortedKeys]) {
            return pretty
        }

        return body
    }

    private static func makeMeta(for response: ParsedResponse) -> VariantMeta? {
        let headers = Dictionary(response.headers, uniquingKeysWith: { first, _ in first })
        let inferredStatus = inferredStatusCode(for: response.name)

        if response.requestMatch == nil,
           response.statusCode == inferredStatus,
           headers.isEmpty {
            return nil
        }

        return VariantMeta(
            statusCode: response.statusCode == inferredStatus ? nil : response.statusCode,
            headers: headers.isEmpty ? nil : headers,
            requestMatch: response.requestMatch
        )
    }

    private static func inferredStatusCode(for variantName: String) -> Int {
        if variantName == "default" {
            return 200
        }
        if variantName.hasPrefix("error-"),
           let code = Int(variantName.dropFirst("error-".count)) {
            return code
        }
        return 200
    }
}
