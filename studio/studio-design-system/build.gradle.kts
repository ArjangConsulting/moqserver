plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(compose.foundation)
	implementation(compose.material3)
	implementation(compose.ui)
}

kotlin {
	jvmToolchain(21)
}
