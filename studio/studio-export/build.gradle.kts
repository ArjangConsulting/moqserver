plugins {
	alias(libs.plugins.kotlin.jvm)
}

dependencies {
	implementation(projects.studioProjectFormat)

	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(21)
}
