plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    // SecretRedactor: agent output is redacted before it is shown on the phone.
    implementation(project(":server:runner"))
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("com.llmmanager.server.api.MainKt")
    applicationName = "api"
    // Start git, tmux and agents with fork() instead of the JDK's jspawnhelper binary: after an
    // in-place JDK package upgrade the running JVM rejects the new helper, and every command
    // (so every repository scan) fails until a restart.
    applicationDefaultJvmArgs = listOf("-Djdk.lang.Process.launchMechanism=FORK")
}
