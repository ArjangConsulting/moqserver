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
	implementation(libs.ktor.client.cio)
	implementation(libs.ktor.client.content.negotiation)
	implementation(libs.ktor.serialization.kotlinx.json)

	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(21)
}
