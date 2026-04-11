package com.moqserver.studio.ai.prompts

import com.moqserver.studio.domain.CompanionRequest

object PromptBuilder {

    private const val DEFAULT_MAX_VARIANTS = 3

    fun buildAnalyzePrompt(request: CompanionRequest): String = buildString {
        appendLine("You are an API specification analyzer. Analyze the following API specification and return structured findings.")
        appendLine()
        appendLine("Return your analysis as a JSON array of findings. Each finding has:")
        appendLine("""- "severity": "info" | "warning" | "error"""")
        appendLine("""- "category": a short category like "naming", "coverage", "auth", "schema"""")
        appendLine("""- "message": what you found""")
        appendLine("""- "endpointKey": optional, e.g. "GET /users"""")
        appendLine("""- "suggestion": optional, what to do about it""")

        request.projectContext?.let { ctx ->
            ctx.title?.let { appendLine(); appendLine("API Title: $it") }
            ctx.version?.let { appendLine("API Version: $it") }
            ctx.endpoints?.let { endpoints ->
                appendLine()
                appendLine("Endpoints:")
                for (ep in endpoints) {
                    var line = "  ${ep.method} ${ep.path}"
                    ep.variantCount?.let { line += " ($it variants)" }
                    if (ep.hasAuth == true) line += " [auth required]"
                    appendLine(line)
                }
            }
            ctx.specExcerpt?.let {
                appendLine()
                appendLine("Spec excerpt:")
                appendLine(it)
            }
        }

        request.intent?.description?.let {
            appendLine()
            appendLine("Focus: $it")
        }

        appendLine()
        append("Respond with ONLY the JSON array, no markdown fences or explanation.")
    }

	@Suppress("CyclomaticComplexMethod")
	fun buildGenerateVariantsPrompt(request: CompanionRequest): String = buildString {
        appendLine("You are a mock API response generator. Generate realistic mock response variants for the specified endpoints.")
        appendLine()
        appendLine("Return your output as a JSON array of variant objects. Each variant has:")
        appendLine("""- "endpointKey": e.g. "GET /users/{id}"""")
        appendLine("""- "name": variant name, e.g. "not-found", "server-error", "empty-list"""")
        appendLine("""- "statusCode": HTTP status code (integer)""")
        appendLine("""- "contentType": e.g. "application/json"""")
        appendLine("""- "body": the mock response body (JSON object, array, or string)""")
        appendLine("""- "description": optional, what this variant represents""")

        request.projectContext?.let { ctx ->
            ctx.title?.let { appendLine(); appendLine("API: $it") }
            ctx.endpoints?.let { endpoints ->
                appendLine()
                appendLine("Endpoints:")
                for (ep in endpoints) {
                    appendLine("  ${ep.method} ${ep.path}")
                }
            }
        }

        request.selection?.endpointKeys?.let { keys ->
            appendLine()
            appendLine("Generate variants for these endpoints only:")
            for (key in keys) appendLine("  $key")
        }

        request.selection?.variantNames?.takeIf { it.isNotEmpty() }?.let { names ->
            appendLine()
            appendLine("Use these variant names when applicable:")
            for (name in names) appendLine("  $name")
        }

        request.intent?.let { intent ->
            intent.description?.let { appendLine(); appendLine("Intent: $it") }
            intent.type?.let { appendLine("Focus on: $it") }
        }

        if (request.intent?.type == "body-generation") {
            appendLine()
            appendLine("This request is to generate or update the response body for an existing variant.")
            appendLine(
                "Return exactly one variant object that matches the selected endpoint and current variant context.",
            )
            appendLine("If the intent includes a current body, treat it as the baseline:")
            appendLine("  - Preserve the exact same JSON schema and structure.")
            appendLine(
                "  - Keep all existing items and fields unless the user explicitly asks to remove or replace them.",
            )
            appendLine("  - If the user asks to add items, append them to the existing collection.")
            appendLine(
                "The \"body\" field in your response should be the actual JSON value (object or array), not a stringified version.",
            )
        }

        val maxVariants = request.options?.maxVariants ?: DEFAULT_MAX_VARIANTS
        if (request.intent?.type != "body-generation") {
            appendLine()
            appendLine("Generate up to $maxVariants variants per endpoint.")
            appendLine("Include error cases (4xx, 5xx) alongside success cases.")
        }
        append("Respond with ONLY the JSON array, no markdown fences or explanation.")
    }

    fun buildRefineProjectPrompt(request: CompanionRequest): String = buildString {
        appendLine("You are an API project structure advisor. Analyze the project and suggest structural improvements.")
        appendLine()
        appendLine("Return your output as a JSON array of suggestion objects. Each suggestion has:")
        appendLine("""- "category": e.g. "naming", "grouping", "fixtures", "coverage", "auth"""")
        appendLine("""- "title": short title for the suggestion""")
        appendLine("""- "description": detailed description of the improvement""")
        appendLine("""- "affectedEndpoints": optional array of endpoint keys affected""")

        request.projectContext?.let { ctx ->
            ctx.title?.let { appendLine(); appendLine("API: $it") }
            ctx.endpoints?.let { endpoints ->
                appendLine()
                appendLine("Current endpoints (${endpoints.size} total):")
                for (ep in endpoints) {
                    var line = "  ${ep.method} ${ep.path}"
                    ep.variantCount?.let { line += " ($it variants)" }
                    ep.tags?.takeIf { it.isNotEmpty() }?.let { line += " [tags: ${it.joinToString()}]" }
                    appendLine(line)
                }
            }
        }

        request.intent?.description?.let {
            appendLine()
            appendLine("Focus: $it")
        }

        appendLine()
        appendLine("Suggest improvements like:")
        appendLine("- Alias cleanup (inconsistent naming)")
        appendLine("- Better grouping or tagging")
        appendLine("- Fixture extraction opportunities")
        appendLine("- Missing error variants")
        appendLine("- Auth configuration gaps")
        append("Respond with ONLY the JSON array, no markdown fences or explanation.")
    }
}
