package com.moqserver.studio.projectformat

import java.io.File

class ProjectRepository(
    private val codec: YamlProjectCodec = YamlProjectCodec(),
) {

    fun load(projectPath: String): MoqProject {
        val dir = File(projectPath)
        require(dir.isDirectory) { "Not a directory: $projectPath" }

        val manifestFile = File(dir, "project.yml")
        require(manifestFile.isFile) { "Missing project.yml at: ${manifestFile.absolutePath}" }
        val manifest = codec.decodeManifest(manifestFile.readText())

        val endpointsDir = File(dir, "endpoints")
        require(endpointsDir.isDirectory) { "Missing endpoints/ directory at: ${endpointsDir.absolutePath}" }

        val endpointFiles = endpointsDir.listFiles { f ->
            f.extension == "yml" || f.extension == "yaml"
        }?.sortedBy { it.name } ?: emptyList()

        require(endpointFiles.isNotEmpty()) { "No endpoint files found in: ${endpointsDir.absolutePath}" }

        val endpoints = endpointFiles.map { file ->
            try {
                codec.decodeEndpoint(file.readText())
            } catch (e: Exception) {
                throw IllegalStateException("Invalid endpoint file ${file.absolutePath}: ${e.message}", e)
            }
        }

        return MoqProject(
            manifest = manifest,
            endpoints = endpoints,
            projectPath = dir.canonicalPath,
        )
    }

    fun save(project: MoqProject, path: String) {
        val dir = File(path)
        val endpointsDir = File(dir, "endpoints")
        val fixturesDir = File(dir, "fixtures")

        dir.mkdirs()
        endpointsDir.mkdirs()
        fixturesDir.mkdirs()

        val manifestYaml = codec.encodeManifest(project.manifest)
        File(dir, "project.yml").writeText(manifestYaml)

        val sortedEndpoints = project.endpoints.sortedBy { it.id }
        val expectedEndpointFiles = sortedEndpoints.map { "${it.id}.yml" }.toSet()
        endpointsDir.listFiles { file ->
            file.isFile && (file.extension == "yml" || file.extension == "yaml")
        }?.forEach { file ->
            if (file.name !in expectedEndpointFiles) {
                file.delete()
            }
        }
        for (endpoint in sortedEndpoints) {
            val endpointYaml = codec.encodeEndpoint(endpoint)
            File(endpointsDir, "${endpoint.id}.yml").writeText(endpointYaml)
        }

        val referencedFixtures = mutableSetOf<String>()
        for (endpoint in project.endpoints) {
            for (variant in endpoint.variants) {
                val bodyFile = variant.bodyFile ?: continue
                referencedFixtures += bodyFile

                val sourceFile = File(project.projectPath, bodyFile)
                val targetFile = File(dir, bodyFile)
                targetFile.parentFile?.mkdirs()

                if (sourceFile.isFile && sourceFile.canonicalPath != targetFile.canonicalPath) {
                    sourceFile.copyTo(targetFile, overwrite = true)
                }
            }
        }

        fixturesDir.walkBottomUp()
            .filter { it != fixturesDir }
            .forEach { file ->
                val relativePath = file.relativeTo(dir).invariantSeparatorsPath
                when {
                    file.isFile && relativePath !in referencedFixtures -> file.delete()
                    file.isDirectory && file.listFiles().isNullOrEmpty() -> file.delete()
                }
            }
    }

    fun readFixture(projectPath: String, bodyFile: String): String? {
        val file = File(projectPath, bodyFile)
        return if (file.isFile) file.readText() else null
    }
}
