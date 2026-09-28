plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately plain Kotlin/JVM. The Android plugin is never applied here, which
// is what lets both the Ktor server and the Android app depend on this module.
dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}
