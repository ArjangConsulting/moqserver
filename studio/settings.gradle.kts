rootProject.name = "moqserver-studio"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":composeApp")
include(":studio-ai")
include(":studio-domain")
include(":studio-data")
include(":studio-import")
include(":studio-code-editor")
include(":studio-export")
include(":studio-logging")
include(":studio-project-format")
include(":studio-ui")
