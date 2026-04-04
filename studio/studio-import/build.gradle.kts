plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	implementation(projects.studioDomain)
	implementation(projects.studioLogging)
	implementation(projects.studioProjectFormat)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.swagger.parser)

	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(21)
}
