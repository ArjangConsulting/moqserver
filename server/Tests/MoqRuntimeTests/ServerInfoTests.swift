import Foundation
import Testing
import Vapor
import VaporTesting
import XCTVapor

@testable import MoqRuntime

struct ServerInfoTests {
    @Test("GET /_admin/version reports the build and every capability")
    func versionRoute() async throws {
        let app = try await buildApp(store: InMemoryMockStore())
        try await app.testing().test(.GET, "/_admin/version") { res async in
            #expect(res.status == .ok)
            let info = try? res.content.decode(ServerInfo.self)
            #expect(info == ServerInfo.current)
            #expect(info?.capabilities.contains("request-query") == true)
        }
        try await app.asyncShutdown()
    }

    @Test("A source build reports itself as unreleased")
    func unstampedBuild() {
        #expect(ServerInfo.current.version == nil)
        #expect(ServerInfo.current.commit == nil)
        #expect(ServerInfo.versionDescription == "unreleased build")
    }

    @Test("Capability names are unique kebab-case")
    func capabilityNames() {
        let names = ServerCapability.allCases.map(\.rawValue)
        #expect(Set(names).count == names.count)
        #expect(names.allSatisfy { $0.range(of: "^[a-z]+(-[a-z]+)*$", options: .regularExpression) != nil })
    }

    @Test("Every capability is documented in ADMIN_API.md")
    func capabilitiesDocumented() throws {
        let docs = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("docs/ADMIN_API.md")
        let text = try String(contentsOf: docs, encoding: .utf8)
        for capability in ServerCapability.allCases {
            #expect(text.contains("`\(capability.rawValue)`"), "\(capability.rawValue) missing from ADMIN_API.md")
        }
    }
}
