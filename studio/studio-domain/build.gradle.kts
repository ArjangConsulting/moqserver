plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.studioProjectFormat)
                implementation(libs.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.lifecycle.viewmodel.compose)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
