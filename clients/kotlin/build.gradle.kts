// Kotlin/JVM test-support client for moqserver — the Android/JVM counterpart of the Swift
// `server/MoqTestSupport` package. A standalone build (not part of studio/) so consumers depend on
// nothing but this library and kotlinx-serialization. Distributed through JitPack by commit SHA;
// see README.md and ../../jitpack.yml.
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-library`
    `maven-publish`
}

// JitPack serves a single-module build as com.github.<owner>:<repo>:<sha>, whatever the
// publication says, so the local coordinates match that to keep mavenLocal and JitPack identical.
group = "com.github.ArjangConsulting"
version = System.getenv("VERSION") ?: "local"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    // 17 is the newest bytecode every supported Android Gradle Plugin desugars; the client uses only
    // java.net APIs available on Android (no java.net.http).
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "moqserver"
            from(components["java"])
        }
    }
}
