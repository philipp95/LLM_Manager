plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt.yml"))
    source.setFrom(files("core", "server"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    include("**/*.kt")
    exclude("**/build/**")
    jvmTarget = "21"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
