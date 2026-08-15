import Foundation
import Testing

@testable import MoqImport

/// Covers the checks `SpecFetcher` performs before ever opening a network connection —
/// scheme/credential validation and literal-IP blocked-address checks (host string parsing runs
/// synchronously; `getaddrinfo`-based hostname resolution is exercised transitively by the
/// `localhost` case, which resolves via the local resolver/hosts file with no external network
/// required). Does not test a successful fetch — that would need a mock HTTP server, out of
/// scope here.
struct SpecFetcherTests {
    @Test("Rejects non-http(s) schemes before connecting")
    func rejectsUnsupportedScheme() async {
        await #expect(throws: SpecFetchError.self) {
            _ = try await SpecFetcher.fetchSpec(from: "ftp://example.com/spec.json")
        }
    }

    @Test("Rejects URLs with embedded credentials")
    func rejectsEmbeddedCredentials() async {
        await #expect(throws: SpecFetchError.self) {
            _ = try await SpecFetcher.fetchSpec(from: "https://user:pass@example.com/spec.json")
        }
    }

    @Test(
        "Rejects literal private/loopback/reserved IPv4 addresses without any network call",
        arguments: [
            "127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
        ]
    )
    func rejectsBlockedIPv4Literals(host: String) async {
        await #expect(throws: SpecFetchError.self) {
            _ = try await SpecFetcher.fetchSpec(from: "http://\(host)/spec.json")
        }
    }

    @Test("Rejects the localhost hostname")
    func rejectsLocalhost() async {
        await #expect(throws: SpecFetchError.self) {
            _ = try await SpecFetcher.fetchSpec(from: "http://localhost/spec.json")
        }
    }

    @Test("Normalizes a bare host by defaulting to https, still enforcing blocklist checks")
    func normalizesBareHostBeforeBlocking() async {
        // No scheme prefix — normalizeURL should prepend https:// and the blocklist check must
        // still fire (proves normalization happens before, not instead of, validation).
        await #expect(throws: SpecFetchError.self) {
            _ = try await SpecFetcher.fetchSpec(from: "127.0.0.1/spec.json")
        }
    }
}
