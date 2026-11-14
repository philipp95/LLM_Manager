pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Toolchain auto-provisioning: the build declares JDK 21 and Gradle downloads it
// if the machine doesn't have one. This repo's dev machine ships JDK 11 only.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "llm-manager"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// --- shared contract -------------------------------------------------------
include(":core:protocol")

// --- server ----------------------------------------------------------------
include(":server:domain")
include(":server:runner")
include(":server:spike")   // M0: CLI harness spike, no Ktor, no Android
include(":server:api")     // read-only dashboard over the tailnet

// --- android ---------------------------------------------------------------
// Included only where an Android SDK is configured, so server work needs no SDK.
// Set ANDROID_HOME or sdk.dir in the untracked local.properties. See docs/environment-setup.md.
val localProperties = java.util.Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: localProperties.getProperty("sdk.dir")
if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":android:app")
}
