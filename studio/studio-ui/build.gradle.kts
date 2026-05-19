plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(projects.studioDesignSystem)
    implementation(projects.studioProjectFormat)
}

kotlin {
    jvmToolchain(21)
}
