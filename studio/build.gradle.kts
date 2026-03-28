plugins {
	alias(libs.plugins.kotlin.multiplatform) apply false
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.compose.multiplatform) apply false
	alias(libs.plugins.compose.compiler) apply false
	alias(libs.plugins.detekt)
}

allprojects {
	group = "com.moqserver"
	version = "1.0.0"
}

subprojects {
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
		compilerOptions {
			allWarningsAsErrors.set(true)
		}
	}

	apply(plugin = "io.gitlab.arturbosch.detekt")

	detekt {
		buildUponDefaultConfig = true
		config.setFrom(rootProject.files("config/detekt/detekt.yml"))
		parallel = true
	}

	tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
		exclude("**/generated/**")
	}

	dependencies {
		"detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:${rootProject.libs.versions.detekt.get()}")
	}
}

detekt {
	buildUponDefaultConfig = true
	config.setFrom(files("config/detekt/detekt.yml"))
	parallel = true
}

dependencies {
	detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

tasks.register("detektAll") {
	description = "Run detekt analysis across all modules including KMP source sets"
	group = "verification"
	dependsOn(
		subprojects.flatMap { subproject ->
			subproject.tasks.matching { it.name.startsWith("detekt") && !it.name.contains("Baseline") && !it.name.contains("GenerateConfig") }
		},
	)
}
