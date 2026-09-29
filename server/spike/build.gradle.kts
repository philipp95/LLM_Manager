plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":server:runner"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.llmmanager.server.spike.MainKt")
}
