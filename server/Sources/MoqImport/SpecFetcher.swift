import Foundation
import Logging

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

private let logger = Logger(label: "moqserver.import.SpecFetcher")

/// Fetches spec content (OpenAPI or otherwise) from a URL, with SSRF hardening: only `http`/
/// `https` are allowed, credentials embedded in the URL are rejected, the resolved host's IP
/// addresses are checked against a private/reserved-range blocklist before connecting,
/// redirects are never followed automatically, and the response is capped in size and time.
///
/// This intentionally does **not** port Kotlin's `OpenAPIURLFetcher` HTML auto-discovery
/// (Swagger UI bundle scraping, well-known-path probing) — that's an interactive-UX nicety for
/// Studio's import screen, not something a headless MCP caller needs; give it the direct spec
/// URL. What this *does* add beyond the Kotlin version is the pre-connect private-address
/// check, which Kotlin's fetcher does not perform at all.
public enum SpecFetcher {
    private static let maxResponseBytes = 50 * 1_048_576  // 50 MiB
    private static let connectTimeout: TimeInterval = 15
    private static let requestTimeout: TimeInterval = 60
    private static let userAgent = "moqserver/1.0"

    public static func fetchSpec(from urlString: String, auth: URLImportAuth? = nil) async throws -> FetchedSpec {
        let normalized = normalizeURL(urlString)
        guard let url = URL(string: normalized) else {
            throw SpecFetchError.invalidURL(normalized)
        }
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            throw SpecFetchError.unsupportedScheme(url.scheme ?? "(none)")
        }
        guard url.user == nil, url.password == nil else {
            throw SpecFetchError.embeddedCredentialsNotAllowed
        }
        guard let host = url.host, !host.isEmpty else {
            throw SpecFetchError.invalidURL(normalized)
        }

        try validateHostIsPubliclyRoutable(host)

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json, application/x-yaml, */*", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = requestTimeout
        applyAuth(auth, to: &request)

        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.timeoutIntervalForRequest = requestTimeout
        sessionConfig.timeoutIntervalForResource = requestTimeout
        let delegate = SizeCappedSessionDelegate(maxBytes: maxResponseBytes)
        let session = URLSession(configuration: sessionConfig, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as SpecFetchError {
            throw error
        } catch {
            throw mapNetworkError(error, host: host)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw SpecFetchError.network("No HTTP response received from \(host).")
        }

        if (300...399).contains(httpResponse.statusCode) {
            throw SpecFetchError.redirectNotFollowed(status: httpResponse.statusCode, url: normalized)
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw SpecFetchError.httpStatus(httpResponse.statusCode, url: normalized)
        }

        guard data.count <= maxResponseBytes else {
            throw SpecFetchError.responseTooLarge(bytes: data.count, limitBytes: maxResponseBytes)
        }
        guard let content = String(data: data, encoding: .utf8) else {
            throw SpecFetchError.notUTF8(url: normalized)
        }

        logger.info("Fetched spec from \(host) (\(data.count) bytes)")
        return FetchedSpec(content: content, resolvedURL: normalized)
    }

    // MARK: - URL normalization

    private static func normalizeURL(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") { return trimmed }
        return "https://\(trimmed)"
    }

    // MARK: - Auth

    private static func applyAuth(_ auth: URLImportAuth?, to request: inout URLRequest) {
        guard let auth else { return }
        switch auth {
        case .bearer(let token):
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .basic(let username, let password):
            let encoded = Data("\(username):\(password)".utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        case .header(let name, let value):
            request.setValue(value, forHTTPHeaderField: name)
        }
    }

    // MARK: - SSRF hardening: resolve and validate before connecting

    /// Resolves `host` via `getaddrinfo` and rejects it if **any** resolved address falls in a
    /// loopback, private, link-local, carrier-grade NAT, multicast, unspecified, or documentation
    /// range (IPv4, IPv6, and IPv4-mapped IPv6).
    ///
    /// This check happens before `URLSession` connects, using a separate resolution pass — there
    /// is an inherent TOCTOU gap between this check and the connection `URLSession` actually
    /// makes (DNS could change, or resolve differently) that only a custom transport binding to
    /// a specific pinned IP could fully close. That's out of scope here; this check still stops
    /// the straightforward case of a spec URL pointing directly at a private/metadata address.
    private static func validateHostIsPubliclyRoutable(_ host: String) throws {
        if let address = IPv4AddressComponents(host), address.isBlockedRange {
            throw SpecFetchError.blockedAddress(host)
        }
        if host.contains(":"), let address = IPv6AddressComponents(host), address.isBlockedRange {
            throw SpecFetchError.blockedAddress(host)
        }

        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        #if canImport(Glibc)
        hints.ai_socktype = Int32(SOCK_STREAM.rawValue)
        #else
        hints.ai_socktype = SOCK_STREAM
        #endif
        var result: UnsafeMutablePointer<addrinfo>?
        defer { if let result { freeaddrinfo(result) } }

        let status = getaddrinfo(host, nil, &hints, &result)
        guard status == 0, let firstResult = result else {
            throw SpecFetchError.hostResolutionFailed(host)
        }

        var current: UnsafeMutablePointer<addrinfo>? = firstResult
        while let info = current {
            if let blocked = blockedAddressDescription(info.pointee) {
                throw SpecFetchError.blockedAddress("\(host) (resolves to \(blocked))")
            }
            current = info.pointee.ai_next
        }
    }

    private static func blockedAddressDescription(_ info: addrinfo) -> String? {
        if info.ai_family == AF_INET {
            var addr = sockaddr_in()
            memcpy(&addr, info.ai_addr, MemoryLayout<sockaddr_in>.size)
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var sinAddr = addr.sin_addr
            inet_ntop(AF_INET, &sinAddr, &buffer, socklen_t(INET_ADDRSTRLEN))
            let ip = String(cString: buffer)
            if let components = IPv4AddressComponents(ip), components.isBlockedRange { return ip }
        } else if info.ai_family == AF_INET6 {
            var addr = sockaddr_in6()
            memcpy(&addr, info.ai_addr, MemoryLayout<sockaddr_in6>.size)
            var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            var sin6Addr = addr.sin6_addr
            inet_ntop(AF_INET6, &sin6Addr, &buffer, socklen_t(INET6_ADDRSTRLEN))
            let ip = String(cString: buffer)
            if let components = IPv6AddressComponents(ip), components.isBlockedRange { return ip }
        }
        return nil
    }

    // MARK: - Error mapping

    private static func mapNetworkError(_ error: Error, host: String) -> SpecFetchError {
        let nsError = error as NSError
        switch nsError.code {
        case NSURLErrorTimedOut:
            return .network("Timed out connecting to \(host).")
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed:
            return .hostResolutionFailed(host)
        case NSURLErrorCannotConnectToHost:
            return .network("Could not connect to \(host). The server may be unreachable.")
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted:
            return .network("Could not establish a secure connection to \(host) (TLS failure).")
        default:
            return .network("Failed to fetch from \(host): \(nsError.localizedDescription)")
        }
    }
}

public struct FetchedSpec: Sendable, Equatable {
    public let content: String
    public let resolvedURL: String
}

public enum URLImportAuth: Sendable, Equatable {
    case bearer(String)
    case basic(username: String, password: String)
    case header(name: String, value: String)
}

public enum SpecFetchError: Error, CustomStringConvertible, Equatable, Sendable {
    case invalidURL(String)
    case unsupportedScheme(String)
    case embeddedCredentialsNotAllowed
    case blockedAddress(String)
    case hostResolutionFailed(String)
    case redirectNotFollowed(status: Int, url: String)
    case httpStatus(Int, url: String)
    case responseTooLarge(bytes: Int, limitBytes: Int)
    case notUTF8(url: String)
    case network(String)

    public var description: String {
        switch self {
        case .invalidURL(let url): return "Invalid URL: \(url)"
        case .unsupportedScheme(let scheme):
            return "Unsupported URL scheme \"\(scheme)\" — only http and https are allowed."
        case .embeddedCredentialsNotAllowed: return "URLs with embedded credentials (user:pass@host) are not allowed."
        case .blockedAddress(let host):
            return "Refusing to fetch from \(host): resolves to a private, loopback, or reserved address."
        case .hostResolutionFailed(let host): return "Could not resolve host: \(host)"
        case .redirectNotFollowed(let status, let url):
            return
                "\(url) responded with a redirect (HTTP \(status)); redirects are not followed. Provide the direct spec URL."
        case .httpStatus(let status, let url): return "\(url) returned HTTP \(status)."
        case .responseTooLarge(let bytes, let limit):
            return "Response too large (\(bytes) bytes, limit \(limit) bytes)."
        case .notUTF8(let url): return "Response from \(url) was not valid UTF-8 text."
        case .network(let message): return message
        }
    }
}

/// Cancels the task once the accumulated response body exceeds the size cap, so a malicious or
/// misbehaving server can't force unbounded memory use by never closing the connection.
private final class SizeCappedSessionDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let maxBytes: Int
    private var receivedBytes = 0

    init(maxBytes: Int) {
        self.maxBytes = maxBytes
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        receivedBytes += data.count
        if receivedBytes > maxBytes {
            dataTask.cancel()
        }
    }

    /// Never follow redirects automatically — treat every 3xx as a terminal response so the
    /// caller sees it and decides, rather than silently landing somewhere the caller (and any
    /// same-origin auth check) never validated.
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

// MARK: - Address range checks

private struct IPv4AddressComponents {
    let octets: (UInt8, UInt8, UInt8, UInt8)

    init?(_ string: String) {
        let parts = string.split(separator: ".")
        guard parts.count == 4 else { return nil }
        var values: [UInt8] = []
        for part in parts {
            guard let value = UInt8(part) else { return nil }
            values.append(value)
        }
        octets = (values[0], values[1], values[2], values[3])
    }

    /// Loopback (127.0.0.0/8), private (10/8, 172.16/12, 192.168/16), link-local (169.254/16,
    /// covers the 169.254.169.254 cloud metadata endpoint), CGNAT (100.64/10), unspecified
    /// (0.0.0.0/8), and documentation (192.0.2/24, 198.51.100/24, 203.0.113/24) ranges.
    var isBlockedRange: Bool {
        let (a, b, _, _) = octets
        if a == 127 { return true }
        if a == 10 { return true }
        if a == 172, (16...31).contains(b) { return true }
        if a == 192, b == 168 { return true }
        if a == 169, b == 254 { return true }
        if a == 100, (64...127).contains(b) { return true }
        if a == 0 { return true }
        if a == 192, b == 0 { return true }
        if a == 198, b == 51 { return true }
        if a == 203, b == 0 { return true }
        if a == 224 || a > 239 { return true }  // multicast + reserved
        return false
    }
}

private struct IPv6AddressComponents {
    let raw: String

    init?(_ string: String) {
        raw = string.lowercased()
    }

    /// Loopback (::1), unspecified (::), unique local (fc00::/7), link-local (fe80::/10), and
    /// IPv4-mapped (::ffff:0:0/96, checked via the embedded IPv4 form) ranges.
    var isBlockedRange: Bool {
        if raw == "::1" || raw == "::" { return true }
        if raw.hasPrefix("fc") || raw.hasPrefix("fd") { return true }
        if raw.hasPrefix("fe8") || raw.hasPrefix("fe9") || raw.hasPrefix("fea") || raw.hasPrefix("feb") { return true }
        if raw.hasPrefix("::ffff:") {
            let mapped = String(raw.dropFirst("::ffff:".count))
            if let v4 = IPv4AddressComponents(mapped) { return v4.isBlockedRange }
        }
        return false
    }
}
