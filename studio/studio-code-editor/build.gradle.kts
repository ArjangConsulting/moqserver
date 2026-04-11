plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.rsyntaxtextarea)
    implementation(projects.studioDesignSystem)
}

kotlin {
    jvmToolchain(21)
}