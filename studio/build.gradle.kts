plugins {
	alias(libs.plugins.kotlin.multiplatform) apply false
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.compose.multiplatform) apply false
	alias(libs.plugins.compose.compiler) apply false
	alias(libs.plugins.detekt)
	alias(libs.plugins.kover)
}

allprojects {
	group = "com.moqserver"
	version = providers.gradleProperty("releaseVersion").get()
}

subprojects {
	apply(plugin = "org.jetbrains.kotlinx.kover")
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
		compilerOptions {
			allWarningsAsErrors.set(true)
		}
	}

	val jdkVersion = rootProject.libs.versions.jdk.get().toInt()
	plugins.withId("org.jetbrains.kotlin.multiplatform") {
		extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
			jvmToolchain(jdkVersion)
		}
	}
	plugins.withId("org.jetbrains.kotlin.jvm") {
		extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
			jvmToolchain(jdkVersion)
		}
	}

	apply(plugin = "io.gitlab.arturbosch.detekt")

	detekt {
		buildUponDefaultConfig = true
		config.setFrom(rootProject.files("config/detekt/detekt.yml"))
		parallel = true
	}

	tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
		// "**/generated/**" doesn't match here: the KMP-registered source root for generated code
		// (e.g. studio-project-format's ProjectModels.generated.kt) already starts below the
		// "generated" directory, so detekt only ever sees paths relative to that root and never
		// sees the "generated" segment itself. Match the file's own naming convention instead.
		exclude("**/generated/**", "**/*.generated.kt")
		// detekt 1.23.8's --jvm-target parser predates JDK 25 (the toolchain pinned above via
		// jdkVersion) and rejects it outright; detekt's own JVM target is independent of the
		// Kotlin compilation toolchain, so pin it to the highest value the parser accepts.
		jvmTarget = "22"
	}

	dependencies {
		"detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:${rootProject.libs.versions.detekt.get()}")
	}

	// CI only keeps the console log; without full exception output a failing assertion shows up
	// as a bare `org.junit.ComparisonFailure` with no expected/actual, which is unactionable
	// from the log alone.
	tasks.withType<Test>().configureEach {
		testLogging {
			events("failed", "skipped")
			exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
			showStackTraces = true
			showCauses = true
		}
	}
}

dependencies {
	subprojects.forEach { kover(it) }
}

kover {
	reports {
		verify {
			// CI's test job previously ran only the bare `test` task, which silently skips KMP
			// modules' jvmTest (studio-domain, studio-project-format) and composeApp's
			// desktopTest — so this bound was calibrated against a fraction of the actual test
			// suite. Now that CI runs `test allTests desktopTest`, the honest aggregate is ~32%
			// (composeApp's UI code has no test harness pulling its coverage down); set a couple
			// points below that measured baseline rather than the old, inflated 35.
			rule("Aggregate line coverage") {
				minBound(30)
			}
		}
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
