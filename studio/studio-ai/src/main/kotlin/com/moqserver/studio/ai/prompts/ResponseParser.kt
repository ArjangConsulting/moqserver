package com.moqserver.studio.ai.prompts

import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.FindingSeverity
import com.moqserver.studio.domain.GenerateVariantsResult
import com.moqserver.studio.domain.GeneratedVariant
import com.moqserver.studio.domain.ProjectSuggestion
import com.moqserver.studio.domain.RefineProjectResult
import com.moqserver.studio.domain.SpecFinding
import kotlinx.serialization.json.Json

object ResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseAnalyzeResponse(text: String): AnalyzeSpecResult {
        val cleaned = stripMarkdownFences(text)
        return try {
            AnalyzeSpecResult(findings = json.decodeFromString(cleaned))
        } catch (_: Exception) {
            AnalyzeSpecResult(findings = listOf(fallbackFinding(text)))
        }
    }

    fun parseGenerateVariantsResponse(text: String): GenerateVariantsResult {
        val cleaned = stripMarkdownFences(text)
        return try {
            GenerateVariantsResult(variants = json.decodeFromString<List<GeneratedVariant>>(cleaned))
        } catch (_: Exception) {
            GenerateVariantsResult(variants = emptyList())
        }
    }

    fun parseRefineProjectResponse(text: String): RefineProjectResult {
        val cleaned = stripMarkdownFences(text)
        return try {
            RefineProjectResult(suggestions = json.decodeFromString<List<ProjectSuggestion>>(cleaned))
        } catch (_: Exception) {
            RefineProjectResult(suggestions = emptyList())
        }
    }

    private fun stripMarkdownFences(text: String): String {
        var result = text.trim()
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline != -1) result = result.substring(firstNewline + 1)
            if (result.endsWith("```")) result = result.dropLast(3)
            result = result.trim()
        }
        return result
    }

    private fun fallbackFinding(rawText: String) = SpecFinding(
        severity = FindingSeverity.INFO,
        category = "general",
        message = rawText,
        endpointKey = null,
        suggestion = null,
    )
}
