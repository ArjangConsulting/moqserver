import Foundation

// MARK: - Prompt Builders

extension CompanionHandler {
    func buildAnalyzePrompt(_ request: CompanionRequest) -> String {
        var parts: [String] = []
        parts.append("You are an API specification analyzer. Analyze the following API specification and return structured findings.")
        parts.append("")
        parts.append("Return your analysis as a JSON array of findings. Each finding has:")
        parts.append(#"- "severity": "info" | "warning" | "error""#)
        parts.append(#"- "category": a short category like "naming", "coverage", "auth", "schema""#)
        parts.append(#"- "message": what you found"#)
        parts.append(#"- "endpointKey": optional, e.g. "GET /users""#)
        parts.append(#"- "suggestion": optional, what to do about it"#)
        parts.append("")

        if let ctx = request.projectContext {
            if let title = ctx.title {
                parts.append("API Title: \(title)")
            }
            if let version = ctx.version {
                parts.append("API Version: \(version)")
            }
            if let endpoints = ctx.endpoints {
                parts.append("")
                parts.append("Endpoints:")
                for ep in endpoints {
                    var line = "  \(ep.method) \(ep.path)"
                    if let count = ep.variantCount { line += " (\(count) variants)" }
                    if let hasAuth = ep.hasAuth, hasAuth { line += " [auth required]" }
                    parts.append(line)
                }
            }
            if let excerpt = ctx.specExcerpt {
                parts.append("")
                parts.append("Spec excerpt:")
                parts.append(excerpt)
            }
        }

        if let intent = request.intent?.description {
            parts.append("")
            parts.append("Focus: \(intent)")
        }

        parts.append("")
        parts.append("Respond with ONLY the JSON array, no markdown fences or explanation.")

        return parts.joined(separator: "\n")
    }

    func buildGenerateVariantsPrompt(_ request: CompanionRequest) -> String {
        var parts: [String] = []
        parts.append("You are a mock API response generator. Generate realistic mock response variants for the specified endpoints.")
        parts.append("")
        parts.append("Return your output as a JSON array of variant objects. Each variant has:")
        parts.append(#"- "endpointKey": e.g. "GET /users/{id}""#)
        parts.append(#"- "name": variant name, e.g. "not-found", "server-error", "empty-list""#)
        parts.append(#"- "statusCode": HTTP status code (integer)"#)
        parts.append(#"- "contentType": e.g. "application/json""#)
        parts.append(#"- "body": the mock response body as a string"#)
        parts.append(#"- "description": optional, what this variant represents"#)
        parts.append("")

        if let ctx = request.projectContext {
            if let title = ctx.title {
                parts.append("API: \(title)")
            }
            if let endpoints = ctx.endpoints {
                parts.append("")
                parts.append("Endpoints:")
                for ep in endpoints {
                    parts.append("  \(ep.method) \(ep.path)")
                }
            }
        }

        if let selection = request.selection?.endpointKeys {
            parts.append("")
            parts.append("Generate variants for these endpoints only:")
            for key in selection {
                parts.append("  \(key)")
            }
        }

        if let intent = request.intent {
            if let desc = intent.description {
                parts.append("")
                parts.append("Intent: \(desc)")
            }
            if let type = intent.type {
                parts.append("Focus on: \(type)")
            }
        }

        let maxVariants = request.options?.maxVariants ?? 3
        parts.append("")
        parts.append("Generate up to \(maxVariants) variants per endpoint.")
        parts.append("Include error cases (4xx, 5xx) alongside success cases.")
        parts.append("Respond with ONLY the JSON array, no markdown fences or explanation.")

        return parts.joined(separator: "\n")
    }

    func buildRefineProjectPrompt(_ request: CompanionRequest) -> String {
        var parts: [String] = []
        parts.append("You are an API project structure advisor. Analyze the project and suggest structural improvements.")
        parts.append("")
        parts.append("Return your output as a JSON array of suggestion objects. Each suggestion has:")
        parts.append(#"- "category": e.g. "naming", "grouping", "fixtures", "coverage", "auth""#)
        parts.append(#"- "title": short title for the suggestion"#)
        parts.append(#"- "description": detailed description of the improvement"#)
        parts.append(#"- "affectedEndpoints": optional array of endpoint keys affected"#)
        parts.append("")

        if let ctx = request.projectContext {
            if let title = ctx.title {
                parts.append("API: \(title)")
            }
            if let endpoints = ctx.endpoints {
                parts.append("")
                parts.append("Current endpoints (\(endpoints.count) total):")
                for ep in endpoints {
                    var line = "  \(ep.method) \(ep.path)"
                    if let count = ep.variantCount { line += " (\(count) variants)" }
                    if let tags = ep.tags, !tags.isEmpty { line += " [tags: \(tags.joined(separator: ", "))]" }
                    parts.append(line)
                }
            }
        }

        if let intent = request.intent?.description {
            parts.append("")
            parts.append("Focus: \(intent)")
        }

        parts.append("")
        parts.append("Suggest improvements like:")
        parts.append("- Alias cleanup (inconsistent naming)")
        parts.append("- Better grouping or tagging")
        parts.append("- Fixture extraction opportunities")
        parts.append("- Missing error variants")
        parts.append("- Auth configuration gaps")
        parts.append("Respond with ONLY the JSON array, no markdown fences or explanation.")

        return parts.joined(separator: "\n")
    }
}

// MARK: - Response Parsers

extension CompanionHandler {
    func parseAnalyzeResponse(_ text: String) -> AnalyzeSpecResult {
        let cleaned = stripMarkdownFences(text)
        guard let data = cleaned.data(using: .utf8) else {
            return AnalyzeSpecResult(findings: [fallbackFinding(text)])
        }
        do {
            let findings = try JSONDecoder().decode([SpecFinding].self, from: data)
            return AnalyzeSpecResult(findings: findings)
        } catch {
            return AnalyzeSpecResult(findings: [fallbackFinding(text)])
        }
    }

    func parseGenerateVariantsResponse(_ text: String) -> GenerateVariantsResult {
        let cleaned = stripMarkdownFences(text)
        guard let data = cleaned.data(using: .utf8) else {
            return GenerateVariantsResult(variants: [])
        }
        do {
            let variants = try JSONDecoder().decode([GeneratedVariant].self, from: data)
            return GenerateVariantsResult(variants: variants)
        } catch {
            return GenerateVariantsResult(variants: [])
        }
    }

    func parseRefineProjectResponse(_ text: String) -> RefineProjectResult {
        let cleaned = stripMarkdownFences(text)
        guard let data = cleaned.data(using: .utf8) else {
            return RefineProjectResult(suggestions: [])
        }
        do {
            let suggestions = try JSONDecoder().decode([ProjectSuggestion].self, from: data)
            return RefineProjectResult(suggestions: suggestions)
        } catch {
            return RefineProjectResult(suggestions: [])
        }
    }

    private func stripMarkdownFences(_ text: String) -> String {
        var result = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Strip ```json ... ``` or ``` ... ```
        if result.hasPrefix("```") {
            if let firstNewline = result.firstIndex(of: "\n") {
                result = String(result[result.index(after: firstNewline)...])
            }
            if result.hasSuffix("```") {
                result = String(result.dropLast(3))
            }
            result = result.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return result
    }

    private func fallbackFinding(_ rawText: String) -> SpecFinding {
        SpecFinding(
            severity: .info,
            category: "general",
            message: rawText,
            endpointKey: nil,
            suggestion: nil
        )
    }
}
