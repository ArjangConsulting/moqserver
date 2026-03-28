plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.studioDomain)
    implementation(projects.studioLogging)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
}

kotlin {
    jvmToolchain(21)
}
