package com.moqserver.studio.export

import com.moqserver.studio.projectformat.MoqProject

/**
 * Builds an [ExportCatalog] from a [MoqProject].
 *
 * Endpoints are sorted alphabetically by reference name.
 * Variants preserve their document order but carry stable metadata.
 */
object ExportCatalogBuilder {

    fun build(project: MoqProject): ExportCatalog {
        val endpoints = project.endpoints
            .map { ep ->
                ExportEndpoint(
                    referenceName = ep.referenceName,
                    method = ep.method,
                    path = ep.path,
                    description = ep.description,
                    variants = ep.variants.map { v ->
                        ExportVariant(
                            referenceName = v.referenceName,
                            name = v.name,
                            status = v.status,
                            isDefault = v.isDefault == true,
                            description = v.description,
                        )
                    },
                )
            }
            .sortedBy { it.referenceName }

        return ExportCatalog(
            projectName = project.manifest.name,
            endpoints = endpoints,
        )
    }
}
