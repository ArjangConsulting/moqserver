package com.moqserver.studio.export.support

import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportVariant

internal object ExportNameAllocator {

    fun endpointNames(
        endpoints: List<ExportEndpoint>,
        normalize: (String) -> String,
        withSuffix: (String, Int) -> String = { base, suffix -> "$base$suffix" },
    ): Map<String, String> {
        return allocate(endpoints.map(ExportEndpoint::referenceName), normalize, withSuffix)
    }

    fun variantNames(
        variants: List<ExportVariant>,
        normalize: (String) -> String,
        withSuffix: (String, Int) -> String,
    ): Map<String, String> {
        return allocate(variants.map(ExportVariant::referenceName), normalize, withSuffix)
    }

    private fun allocate(
        referenceNames: List<String>,
        normalize: (String) -> String,
        withSuffix: (String, Int) -> String,
    ): Map<String, String> {
        val allocated = linkedMapOf<String, String>()
        val usedNames = mutableSetOf<String>()

        referenceNames.forEach { referenceName ->
            val baseName = normalize(referenceName)
            var candidate = baseName
            var suffix = 2

            while (!usedNames.add(candidate)) {
                candidate = withSuffix(baseName, suffix)
                suffix += 1
            }

            allocated[referenceName] = candidate
        }

        return allocated
    }
}

internal object ExportComments {

    fun lines(text: String?): List<String> {
        if (text.isNullOrBlank()) {
            return emptyList()
        }

        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.replace("*/", "* /") }
    }
}
