plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":server:runner"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.llmmanager.server.spike.MainKt")
}
