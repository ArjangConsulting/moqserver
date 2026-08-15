plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val moqProjectExtension = "moqproj"
val moqProjectMimeType = "application/x-moqserver-project"
val moqProjectTypeIdentifier = "com.moqserver.project"
val moqProjectTypeDescription = "moqserver Project"
val macBundleIconName = "moqserver-studio.icns"
val macAppIconFile = "src/desktopMain/resources/icons/icon.icns"
val linuxAppIconFile = "src/desktopMain/resources/icons/icon.png"
val nativePackageVersion = project.version.toString().substringBefore('-').substringBefore('+')

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.studioDesignSystem)
                implementation(projects.studioDomain)
                implementation(projects.studioProjectFormat)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.navigation.compose)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(projects.studioAi)
                implementation(projects.studioData)
                implementation(projects.studioExport)
                implementation(projects.studioProjectFormat)
                implementation(projects.studioCodeEditor)
                implementation(projects.studioLogging)
                implementation(projects.studioUi)
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.tooling.preview.desktop)
                implementation(libs.coroutines.swing)
                implementation(libs.slf4j.simple)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.moqserver.studio.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=moqserver Studio",
            "-Dmoqserver.studio.version=${project.version}",
        )

        nativeDistributions {
            packageName = "moqserver-studio"
            packageVersion = nativePackageVersion
            description = "Desktop authoring tool for moqserver projects"
            vendor = "moqserver"
            copyright = "Copyright 2026 moqserver contributors"
            licenseFile.set(project.rootProject.file("../LICENSE"))
            // Where CI places the platform-matching moq-format binary before packaging (see
            // release.yml's studio-macos/studio-linux jobs and app-resources/README.md — files
            // must sit under a per-platform subdirectory like macos-arm64/, not this directory's
            // root, which Compose silently ignores). Compose stages this into the distribution
            // and points `compose.application.resources.dir` at the runtime location for both
            // `:run` and a packaged, installed app — see FormatBinaryLocator, which reads that
            // property. Empty locally unless a developer populates it by hand; Studio still runs
            // fine without it as long as MOQSERVER_FORMAT_BINARY or PATH resolves moq-format.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))
            // Windows is out of scope indefinitely (no Swift toolchain support planned), so it
            // carries no target format, no windows{} packaging block, and no CI job.
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )

            macOS {
                bundleID = "com.moqserver.studio"
                appCategory = "public.app-category.developer-tools"
                iconFile.set(project.file(macAppIconFile))
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>CFBundleName</key>
                        <string>moqserver Studio</string>
                        <key>CFBundleDisplayName</key>
                        <string>moqserver Studio</string>
                        <key>UTExportedTypeDeclarations</key>
                        <array>
                          <dict>
                            <key>UTTypeIdentifier</key>
                            <string>$moqProjectTypeIdentifier</string>
                            <key>UTTypeDescription</key>
                            <string>$moqProjectTypeDescription</string>
                            <key>UTTypeConformsTo</key>
                            <array>
                              <string>com.apple.package</string>
                            </array>
                            <key>UTTypeTagSpecification</key>
                            <dict>
                              <key>public.filename-extension</key>
                              <array>
                                <string>$moqProjectExtension</string>
                              </array>
                            </dict>
                          </dict>
                        </array>
                        <key>CFBundleDocumentTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleTypeName</key>
                            <string>$moqProjectTypeDescription</string>
                            <key>CFBundleTypeRole</key>
                            <string>Editor</string>
                            <key>LSHandlerRank</key>
                            <string>Owner</string>
                            <key>LSTypeIsPackage</key>
                            <true/>
                            <key>LSItemContentTypes</key>
                            <array>
                              <string>$moqProjectTypeIdentifier</string>
                            </array>
                            <key>CFBundleTypeExtensions</key>
                            <array>
                              <string>$moqProjectExtension</string>
                            </array>
                            <key>CFBundleTypeIconFile</key>
                            <string>$macBundleIconName</string>
                          </dict>
                        </array>
                        """.trimIndent()
                }
                // Code signing — set MOQSERVER_STUDIO_SIGNING_IDENTITY env var to enable
                // e.g. "Developer ID Application: Your Name (TEAMID)"
                val signingIdentity = System.getenv("MOQSERVER_STUDIO_SIGNING_IDENTITY")
                if (!signingIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(signingIdentity)
                    }
                }
                // Notarization (notarytool) — requires Apple ID, an app-specific password, and Team ID.
                // Set MOQSERVER_STUDIO_APPLE_ID, MOQSERVER_STUDIO_NOTARIZATION_PASSWORD, MOQSERVER_STUDIO_TEAM_ID.
                val appleId = System.getenv("MOQSERVER_STUDIO_APPLE_ID")
                val notarizationPassword = System.getenv("MOQSERVER_STUDIO_NOTARIZATION_PASSWORD")
                val teamId = System.getenv("MOQSERVER_STUDIO_TEAM_ID")
                if (!appleId.isNullOrBlank() && !notarizationPassword.isNullOrBlank() && !teamId.isNullOrBlank()) {
                    notarization {
                        this.appleID.set(appleId)
                        this.password.set(notarizationPassword)
                        this.teamID.set(teamId)
                    }
                }
            }

            linux {
                iconFile.set(project.file(linuxAppIconFile))
                fileAssociation(
                    mimeType = moqProjectMimeType,
                    extension = moqProjectExtension,
                    description = moqProjectTypeDescription,
                    iconFile = project.file(linuxAppIconFile),
                )
                debMaintainer = "moqserver contributors"
                menuGroup = "Development"
                appRelease = "1"
                appCategory = "devel"
            }
        }
    }
}

tasks.register("registerMacApp") {
    group = "distribution"
    description = "Register the built macOS app bundle with Launch Services."
    dependsOn("createDistributable")
    onlyIf {
        System.getProperty("os.name").contains("mac", ignoreCase = true)
    }
    doLast {
        val appBundle = layout.buildDirectory
            .dir("compose/binaries/main/app/moqserver-studio.app")
            .get()
            .asFile
        check(appBundle.isDirectory) {
            "App bundle not found at ${appBundle.absolutePath}"
        }

        providers.exec {
            commandLine(
                "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
                "-f",
                appBundle.absolutePath,
            )
        }.result.get()
    }
}

tasks.register("packageReleaseForCurrentOS") {
    group = "distribution"
    description = "Build a release package for the current OS. On macOS this expects signing to be configured via environment variables."
    dependsOn("packageDistributionForCurrentOS")
    doFirst {
        if (System.getProperty("os.name").contains("mac", ignoreCase = true) &&
            System.getenv("MOQSERVER_STUDIO_SIGNING_IDENTITY").isNullOrBlank()
        ) {
            error("MOQSERVER_STUDIO_SIGNING_IDENTITY must be set for macOS release packaging.")
        }
    }
}
