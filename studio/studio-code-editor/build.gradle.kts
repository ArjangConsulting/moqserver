plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.rsyntaxtextarea)
    implementation(projects.studioDesignSystem)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // Swing/RSyntaxTextArea construction must run without a display in CI.
    systemProperty("java.awt.headless", "true")
}

kotlin {

}