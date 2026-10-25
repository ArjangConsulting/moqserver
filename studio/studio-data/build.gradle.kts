plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.studioDomain)
    implementation(projects.studioLogging)
    implementation(projects.studioProjectFormat)
    api(libs.credential.keychain)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.testcommons.core)
}

kotlin {

}
