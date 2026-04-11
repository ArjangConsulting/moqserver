package com.moqserver.studio.ai.prompts

import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.FindingSeverity
import com.moqserver.studio.domain.GenerateVariantsResult
import com.moqserver.studio.domain.GeneratedVariant
import com.moqserver.studio.domain.ProjectSuggestion
import com.moqserver.studio.domain.RefineProjectResult
import com.moqserver.studio.domain.SpecFinding
import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object ResponseParser {

    private val logger = loggerFor<ResponseParser>()
    private val json = Json { ignoreUnknownKeys = true }

    fun parseAnalyzeResponse(text: String): AnalyzeSpecResult {
        logger.debug("Parsing analyze response ({} chars)", text.length)
        val cleaned = sanitizeResponse(text)
        val candidates = listOfNotNull(
            cleaned,
            unwrapNamedArray(cleaned, "findings"),
            extractJsonArray(cleaned),
        ).distinct()

        for (candidate in candidates) {
            try {
                val result = AnalyzeSpecResult(findings = json.decodeFromString(candidate))
                logger.debug("Parsed {} findings from analyze response", result.findings.size)
                return result
            } catch (_: Exception) {
                // Try the next candidate shape.
            }
        }

        logger.warn("Could not parse structured analyze response, using fallback")
        return AnalyzeSpecResult(findings = listOf(fallbackFinding(cleaned.ifBlank { text })))
    }

    fun parseGenerateVariantsResponse(text: String): GenerateVariantsResult {
        logger.debug("Parsing generate-variants response ({} chars)", text.length)
        val cleaned = sanitizeResponse(text)
        val candidates = listOfNotNull(
            cleaned,
            unwrapNamedArray(cleaned, "variants"),
            unwrapNamedArray(cleaned, "draftVariants"),
            unwrapNamedArray(cleaned, "draft_variants"),
            extractJsonArray(cleaned),
        ).distinct()

        for (candidate in candidates) {
            try {
                val result = GenerateVariantsResult(variants = json.decodeFromString<List<GeneratedVariant>>(candidate))
                logger.debug("Parsed {} variants from generate response", result.variants.size)
                return result
            } catch (_: Exception) {
                // Try the next candidate. Ollama sometimes wraps JSON with extra text.
            }
        }

        logger.warn("Could not parse structured variants response, using fallback")
        val fallbackVariant = fallbackGeneratedVariant(cleaned.ifBlank { text })
        return GenerateVariantsResult(variants = listOf(fallbackVariant))
    }

    fun parseRefineProjectResponse(text: String): RefineProjectResult {
        logger.debug("Parsing refine-project response ({} chars)", text.length)
        val cleaned = sanitizeResponse(text)
        val candidates = listOfNotNull(
            cleaned,
            unwrapNamedArray(cleaned, "suggestions"),
            extractJsonArray(cleaned),
        ).distinct()

        for (candidate in candidates) {
            try {
                val result = RefineProjectResult(suggestions = json.decodeFromString(candidate))
                logger.debug("Parsed {} suggestions from refine response", result.suggestions.size)
                return result
            } catch (_: Exception) {
                // Try the next candidate shape.
            }
        }

        logger.warn("Could not parse structured refine response, using fallback")
        return RefineProjectResult(suggestions = listOf(fallbackSuggestion(cleaned.ifBlank { text })))
    }

    private fun sanitizeResponse(text: String): String {
        return stripMarkdownFences(stripThinkBlocks(text))
    }

    private fun stripThinkBlocks(text: String): String {
        return THINK_TAG_REGEX.replace(text, "").trim()
    }

    private fun stripMarkdownFences(text: String): String {
        var result = text.trim()
        if (result.startsWith(MARKDOWN_FENCE)) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline != -1) result = result.substring(firstNewline + 1)
            if (result.endsWith(MARKDOWN_FENCE)) result = result.dropLast(MARKDOWN_FENCE.length)
            result = result.trim()
        }
        return result
    }

    private fun unwrapNamedArray(text: String, key: String): String? {
        return try {
            val element = json.parseToJsonElement(text)
            val value = (element as? JsonObject)?.get(key) as? JsonArray ?: return null
            value.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaping = false

        for (index in start until text.length) {
            val character = text[index]

            if (inString) {
                when {
                    escaping -> escaping = false
                    character == '\\' -> escaping = true
                    character == '"' -> inString = false
                }
                continue
            }

            when (character) {
                '"' -> inString = true
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun fallbackFinding(rawText: String) = SpecFinding(
        severity = FindingSeverity.INFO,
        category = FALLBACK_CATEGORY,
        message = rawText.trim(),
        endpointKey = null,
        suggestion = null,
    )

    private fun fallbackGeneratedVariant(rawText: String): GeneratedVariant {
        return GeneratedVariant(
            endpointKey = FALLBACK_ENDPOINT_KEY,
            name = FALLBACK_VARIANT_NAME,
            statusCode = 200,
            contentType = "text/plain",
            body = rawText.trim(),
            description = "Fallback variant generated from unstructured AI output.",
        )
    }

    private fun fallbackSuggestion(rawText: String) = ProjectSuggestion(
        category = FALLBACK_CATEGORY,
        title = "Review AI output",
        description = rawText.trim(),
        affectedEndpoints = null,
    )

    private const val MARKDOWN_FENCE = "```"
    private const val FALLBACK_CATEGORY = "general"
    private const val FALLBACK_ENDPOINT_KEY = "UNMAPPED"
    private const val FALLBACK_VARIANT_NAME = "generated-response"
    private val THINK_TAG_REGEX = Regex(
        """<think>.*?</think>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
}
