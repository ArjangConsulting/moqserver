plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(libs.compose.foundation)
	implementation(compose.material3)
	implementation(libs.compose.ui)
	implementation(libs.kmp.components)
}

kotlin {

}
