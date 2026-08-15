plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":studio-logging"))
                implementation(libs.snakeyaml.engine)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
