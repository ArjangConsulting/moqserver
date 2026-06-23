import Foundation
import Logging
import MoqCore
import Yams

private let logger = Logger(label: "moqserver.format.ProjectWriter")

/// Writes a MoqProject to a .moqproj directory with deterministic output.
public struct ProjectWriter: ProjectWriting {
    public init() {}

    public func write(_ project: MoqProject, to path: String) throws {
        logger.info("Writing project '\(project.manifest.name)' to \(path)")
        let fm = FileManager.default
        let projectPath = (path as NSString).standardizingPath

        // Create directory structure
        let endpointsDir = (projectPath as NSString).appendingPathComponent("endpoints")
        let fixturesDir = (projectPath as NSString).appendingPathComponent("fixtures")
        try fm.createDirectory(atPath: endpointsDir, withIntermediateDirectories: true)
        try fm.createDirectory(atPath: fixturesDir, withIntermediateDirectories: true)

        // Write project.yml with deterministic key ordering
        let manifestYAML = try encodeManifest(project.manifest)
        let manifestPath = (projectPath as NSString).appendingPathComponent("project.yml")
        try manifestYAML.write(toFile: manifestPath, atomically: true, encoding: .utf8)

        // Write endpoint files sorted by id
        let sortedEndpoints = project.endpoints.sorted { $0.id < $1.id }
        for endpoint in sortedEndpoints {
            let endpointYAML = try encodeEndpoint(endpoint)
            let fileName = "\(endpoint.id).yml"
            let filePath = (endpointsDir as NSString).appendingPathComponent(fileName)
            logger.debug("Writing endpoint file \(fileName)")
            try endpointYAML.write(toFile: filePath, atomically: true, encoding: .utf8)
        }
        logger.info("Project written: \(sortedEndpoints.count) endpoint file(s)")
    }

    // MARK: - Deterministic Encoding

    private func encodeManifest(_ manifest: ProjectManifest) throws -> String {
        var lines: [String] = []
        lines.append("version: \"\(manifest.version)\"")
        lines.append("name: \(yamlQuote(manifest.name))")
        if let desc = manifest.description {
            lines.append("description: \(yamlQuote(desc))")
        }
        lines.append("")
        lines.append("defaults:")
        lines.append("  delay_ms: \(manifest.defaults.delayMs)")
        lines.append("  auth:")
        lines.append(contentsOf: encodeAuth(manifest.defaults.auth, indent: 4))
        lines.append("  network:")
        lines.append(contentsOf: encodeNetwork(manifest.defaults.network, indent: 4))

        if let rules = manifest.globalRules {
            lines.append("")
            lines.append("global_rules:")
            if let headers = rules.requiredHeaders, !headers.isEmpty {
                lines.append("  required_headers:")
                for matcher in headers {
                    lines.append(contentsOf: encodeRuleMatcher(matcher, indent: 4))
                }
            } else {
                lines.append("  required_headers: []")
            }
            lines.append("  verify_cookies: \(rules.verifyCookies ?? false)")
        }

        return lines.joined(separator: "\n") + "\n"
    }

    private func encodeEndpoint(_ endpoint: EndpointDocument) throws -> String {
        var lines: [String] = []

        // Key ordering per FORMAT_IMPLEMENTATION.md
        lines.append("id: \(endpoint.id)")
        let alias =
            endpoint.alias
            ?? EndpointAlias.defaultAlias(
                method: endpoint.method,
                path: endpoint.path,
                operation: endpoint.operation
            )
        lines.append("alias: \(yamlQuote(alias))")
        if let description = endpoint.description {
            lines.append("description: \(yamlQuote(description))")
        }
        lines.append("reference_name: \(yamlQuote(endpoint.referenceName))")
        lines.append("method: \(endpoint.method)")
        lines.append("path: \(endpoint.path)")
        if let tags = endpoint.tags, !tags.isEmpty {
            lines.append("tags: [\(tags.joined(separator: ", "))]")
        }

        if let operation = endpoint.operation {
            lines.append("")
            lines.append("operation:")
            lines.append("  type: \(operation.type.rawValue)")
            if let name = operation.name {
                lines.append("  name: \(name)")
            }
            if let document = operation.document {
                lines.append("  document: |")
                for docLine in document.split(separator: "\n", omittingEmptySubsequences: false) {
                    lines.append("    \(docLine)")
                }
            }
        }

        if let auth = endpoint.auth {
            lines.append("")
            lines.append("auth:")
            lines.append(contentsOf: encodeAuth(auth, indent: 2))
        }

        if let rules = endpoint.requestRules {
            lines.append("")
            lines.append("request_rules:")
            if let headers = rules.headers, !headers.isEmpty {
                lines.append("  headers:")
                for matcher in headers {
                    lines.append(contentsOf: encodeRuleMatcher(matcher, indent: 4))
                }
            }
            if let verifyCookies = rules.verifyCookies {
                lines.append("  verify_cookies: \(verifyCookies)")
            }
            if let queryParams = rules.queryParams, !queryParams.isEmpty {
                lines.append("  query_params:")
                for matcher in queryParams {
                    lines.append(contentsOf: encodeRuleMatcher(matcher, indent: 4))
                }
            } else if rules.queryParams != nil {
                lines.append("  query_params: []")
            }
            if let cookies = rules.cookies, !cookies.isEmpty {
                lines.append("  cookies:")
                for matcher in cookies {
                    lines.append(contentsOf: encodeRuleMatcher(matcher, indent: 4))
                }
            } else if rules.cookies != nil {
                lines.append("  cookies: []")
            }
        }

        lines.append("")
        lines.append("variants:")
        for variant in endpoint.variants {
            lines.append(contentsOf: try encodeVariant(variant, indent: 2))
        }

        if let network = endpoint.network {
            lines.append("")
            lines.append("network:")
            lines.append(contentsOf: encodeNetwork(network, indent: 2))
        }

        return lines.joined(separator: "\n") + "\n"
    }

    private func encodeVariant(_ variant: ProjectVariant, indent: Int) throws -> [String] {
        let pad = String(repeating: " ", count: indent)
        var lines: [String] = []

        lines.append("\(pad)- name: \(variant.name)")
        lines.append("\(pad)  reference_name: \(yamlQuote(variant.referenceName))")
        if let isDefault = variant.isDefault, isDefault {
            lines.append("\(pad)  default: true")
        }
        lines.append("\(pad)  status: \(variant.status)")

        if let headers = variant.headers, !headers.isEmpty {
            lines.append("\(pad)  headers:")
            for (key, value) in headers.sorted(by: { $0.key < $1.key }) {
                lines.append("\(pad)    \(key): \(value)")
            }
        }

        if let requestMatch = variant.requestMatch,
            !requestMatch.query.isEmpty || !requestMatch.headers.isEmpty || requestMatch.bodyContains != nil
        {
            lines.append("\(pad)  request_match:")
            if !requestMatch.query.isEmpty {
                lines.append("\(pad)    query:")
                for (key, value) in requestMatch.query.sorted(by: { $0.key < $1.key }) {
                    lines.append("\(pad)      \(key): \(yamlQuote(value))")
                }
            }
            if !requestMatch.headers.isEmpty {
                lines.append("\(pad)    headers:")
                for (key, value) in requestMatch.headers.sorted(by: { $0.key < $1.key }) {
                    lines.append("\(pad)      \(key): \(yamlQuote(value))")
                }
            }
            if let bodyContains = requestMatch.bodyContains {
                lines.append("\(pad)    body_contains: \(yamlQuote(bodyContains))")
            }
        }

        if let bodyFile = variant.bodyFile {
            lines.append("\(pad)  body_file: \(bodyFile)")
        } else if let body = variant.body {
            switch body {
            case .null, .bool, .int, .double, .string:
                // Scalar: inline on same line
                lines.append("\(pad)  body: \(encodeBodyValue(body))")
            case .array:
                // Array: flow style on same line
                lines.append("\(pad)  body: \(encodeFlowValue(body))")
            case .object(let dict) where dict.isEmpty:
                lines.append("\(pad)  body: {}")
            case .object:
                // Object: always block style on next line
                lines.append("\(pad)  body:")
                let bodyYAML = encodeBodyValue(body)
                for bodyLine in bodyYAML.split(separator: "\n", omittingEmptySubsequences: false) {
                    lines.append("\(pad)    \(bodyLine)")
                }
            }
        }

        if let delayMs = variant.delayMs {
            lines.append("\(pad)  delay_ms: \(delayMs)")
        }

        return lines
    }

    private func encodeAuth(_ auth: ProjectAuthConfig, indent: Int) -> [String] {
        let pad = String(repeating: " ", count: indent)
        var lines: [String] = []
        lines.append("\(pad)type: \(auth.type.rawValue)")
        lines.append("\(pad)verify: \(auth.verify)")
        if let headerName = auth.headerName {
            lines.append("\(pad)header_name: \(headerName)")
        } else {
            lines.append("\(pad)header_name: null")
        }
        return lines
    }

    private func encodeNetwork(_ network: NetworkBehavior, indent: Int) -> [String] {
        let pad = String(repeating: " ", count: indent)
        var lines: [String] = []
        lines.append("\(pad)latency_ms: \(network.latencyMs ?? 0)")
        lines.append("\(pad)jitter_ms: \(network.jitterMs ?? 0)")
        lines.append("\(pad)packet_loss_percent: \(network.packetLossPercent ?? 0)")
        return lines
    }

    private func encodeRuleMatcher(_ matcher: RuleMatcher, indent: Int) -> [String] {
        let pad = String(repeating: " ", count: indent)
        var lines: [String] = []
        lines.append("\(pad)- name: \(matcher.name)")
        if let matchType = matcher.matchType {
            lines.append("\(pad)  match_type: \(matchType.rawValue)")
        }
        if let match = matcher.match {
            lines.append("\(pad)  match: \(yamlQuote(match))")
        }
        if let required = matcher.required {
            lines.append("\(pad)  required: \(required)")
        }
        return lines
    }

    /// Encode a body value as YAML block style.
    /// Returns lines (without leading indent — caller handles outer indentation).
    private func encodeBodyValue(_ value: AnyCodableValue, baseIndent: Int = 0) -> String {
        switch value {
        case .null:
            return "null"
        case .bool(let v):
            return v ? "true" : "false"
        case .int(let v):
            return "\(v)"
        case .double(let v):
            return "\(v)"
        case .string(let v):
            return yamlQuote(v)
        case .array(let arr):
            if arr.isEmpty { return "[]" }
            // Always use flow style for arrays to avoid block-in-block issues
            return encodeFlowValue(value)
        case .object(let dict):
            if dict.isEmpty { return "{}" }
            var lines: [String] = []
            for (key, val) in dict.sorted(by: { $0.key < $1.key }) {
                switch val {
                case .object(let nested) where !nested.isEmpty:
                    lines.append("\(key):")
                    let subYAML = encodeBodyValue(val, baseIndent: baseIndent + 2)
                    for subLine in subYAML.split(separator: "\n", omittingEmptySubsequences: false) {
                        lines.append("  \(subLine)")
                    }
                case .array(let arr) where !arr.isEmpty:
                    // Use flow style for arrays
                    lines.append("\(key): \(encodeFlowValue(val))")
                default:
                    lines.append("\(key): \(encodeBodyValue(val, baseIndent: baseIndent))")
                }
            }
            return lines.joined(separator: "\n")
        }
    }

    /// Encode a value in YAML flow (JSON-like) style — safe for any nesting depth.
    private func encodeFlowValue(_ value: AnyCodableValue) -> String {
        switch value {
        case .null:
            return "null"
        case .bool(let v):
            return v ? "true" : "false"
        case .int(let v):
            return "\(v)"
        case .double(let v):
            return "\(v)"
        case .string(let v):
            return yamlQuote(v)
        case .array(let arr):
            if arr.isEmpty { return "[]" }
            let items = arr.map { encodeFlowValue($0) }
            return "[\(items.joined(separator: ", "))]"
        case .object(let dict):
            if dict.isEmpty { return "{}" }
            let items = dict.sorted(by: { $0.key < $1.key }).map { key, val in
                "\(key): \(encodeFlowValue(val))"
            }
            return "{\(items.joined(separator: ", "))}"
        }
    }

    private func yamlQuote(_ string: String) -> String {
        if string.contains("\"") || string.contains("\n") || string.contains(":") || string.contains("#") {
            let escaped = string.replacingOccurrences(of: "\"", with: "\\\"")
            return "\"\(escaped)\""
        }
        if string.isEmpty || string.hasPrefix(" ") || string.hasSuffix(" ") {
            return "\"\(string)\""
        }
        // Quote if it looks like a YAML special value
        let lower = string.lowercased()
        if ["true", "false", "null", "yes", "no", "on", "off"].contains(lower) {
            return "\"\(string)\""
        }
        return "\"\(string)\""
    }
}
