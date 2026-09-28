plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure domain: no I/O, no framework. Keeps the rules testable without a server.
dependencies {
    api(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}
