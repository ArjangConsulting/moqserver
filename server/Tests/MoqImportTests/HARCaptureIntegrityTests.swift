import Foundation
import Testing

@testable import MoqImport

struct HARCaptureIntegrityTests {
    @Test(arguments: ["0", "null", "99", "600"])
    func doesNotTurnInvalidStatusesIntoSuccess(_ status: String) throws {
        let spec = try HARImporter.parse(har(status: status))
        #expect(spec.endpoints.map(\.path) == ["/control"])
        #expect(spec.warnings.count == 1)
    }

    @Test(arguments: [
        #""_capture":{"isComplete":false,"isTruncated":false},"#,
        #""_capture":{"isComplete":true,"isTruncated":true},"#,
    ])
    func refusesIncompleteCaptures(_ metadata: String) throws {
        let spec = try HARImporter.parse(har(metadata: metadata))
        #expect(spec.endpoints.map(\.path) == ["/control"])
        #expect(spec.warnings.count == 1)
    }

    @Test func refusesFailureAfterResponseHeaders() throws {
        let spec = try HARImporter.parse(har(entryMetadata: #""_error":"cancelled","#))
        #expect(spec.endpoints.map(\.path) == ["/control"])
        #expect(spec.warnings.count == 1)
    }

    @Test func acceptsCompleteAndLegacyCaptures() throws {
        for metadata in ["", #""_capture":{"isComplete":true,"isTruncated":false},"#] {
            let spec = try HARImporter.parse(har(metadata: metadata))
            #expect(spec.endpoints.count == 2)
            #expect(spec.warnings.isEmpty)
        }
    }

    private func har(status: String = "200", metadata: String = "", entryMetadata: String = "") -> String {
        """
        {"log":{"entries":[{\(entryMetadata)
          "request":{"method":"GET","url":"https://example.test/events"},
          "response":{\(metadata)"status":\(status),"content":{"mimeType":"text/event-stream","text":"data: ok"}}
        },{"request":{"method":"GET","url":"https://example.test/control"},
        "response":{"status":204,"content":{}}}]}}
        """
    }
}
