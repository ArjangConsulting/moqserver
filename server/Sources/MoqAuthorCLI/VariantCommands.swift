import ArgumentParser
import Foundation
import MoqCore
import MoqFormat
import MoqService

public struct VariantUpsertCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "upsert",
        abstract: "Create or replace one variant on an endpoint from a JSON file (or - for stdin)"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(
        name: .long,
        help: ArgumentHelp(
            "Path to a JSON file matching the variant schema, plus endpoint_id — e.g. "
                + "{\"endpoint_id\": \"get-users\", \"name\": \"success\", \"status\": 200, \"default\": true, "
                + "\"body\": {...}}. Never set body_file — inline body only; the store externalizes it to a "
                + "fixture at save time. Pass - to read from stdin. Variant names match case-insensitively: "
                + "upserting an existing name (any casing) REPLACES it — check the printed \"outcome\".")
    )
    var json: String

    public init() {}

    public mutating func run() async throws {
        do {
            let data = try readJSONInput(json)
            let ref = try JSONDecoder().decode(VariantEndpointRef.self, from: data)
            let variant = try JSONDecoder().decode(ProjectVariant.self, from: data)
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let outcome = try await service.upsertVariant(
                handle: handle, endpointID: ref.endpointId, variant: variant, autosave: true)
            try printJSON(VariantUpsertResult(outcome: outcome, variant: variant))
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

public struct VariantRemoveCommand: AsyncParsableCommand {
    public static let configuration = CommandConfiguration(
        commandName: "remove",
        abstract: "Remove one variant from an endpoint (matched case-insensitively)"
    )

    @Option(name: .long, help: "Path to a .moqproj bundle")
    var project: String

    @Option(name: .long, help: "Endpoint id the variant belongs to")
    var endpointId: String

    @Option(name: .long, help: "Variant name to remove (matched case-insensitively)")
    var name: String

    public init() {}

    public mutating func run() async throws {
        do {
            let (service, handle) = try await openExistingProject(project, allowNetworkImport: false)
            let removedName = try await service.removeVariant(
                handle: handle, input: RemoveVariantInput(endpointId: endpointId, name: name), autosave: true)
            try printJSON(["removed": removedName])
        } catch {
            printError(error)
            throw ExitCode.failure
        }
    }
}

private struct VariantEndpointRef: Decodable {
    let endpointId: String
    enum CodingKeys: String, CodingKey { case endpointId = "endpoint_id" }
}

private struct VariantUpsertResult: Encodable {
    let outcome: String
    let previousName: String?
    let variant: ProjectVariant

    enum CodingKeys: String, CodingKey {
        case outcome
        case previousName = "previous_name"
        case variant
    }

    init(outcome: VariantUpsertOutcome, variant: ProjectVariant) {
        switch outcome {
        case .created:
            self.outcome = "created"
            self.previousName = nil
        case .replaced(let previousName):
            self.outcome = "replaced"
            self.previousName = previousName
        }
        self.variant = variant
    }
}
