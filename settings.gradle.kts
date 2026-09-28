// Toolchain auto-provisioning: the build declares JDK 21 and Gradle downloads it
// if the machine doesn't have one. This repo's dev machine ships JDK 11 only.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "llm-manager"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// --- shared contract -------------------------------------------------------
include(":core:protocol")

// --- server ----------------------------------------------------------------
include(":server:domain")
include(":server:runner")
include(":server:spike")   // M0: CLI harness spike, no Ktor, no Android

// Android modules are deliberately absent until the Android SDK is installed.
// See docs/environment-setup.md.
