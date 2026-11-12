import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Machine-specific values (server URL, signing key) live in the untracked local.properties.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun setting(name: String): String? = (findProperty(name) as String?) ?: local.getProperty(name)

val releaseStore = setting("llmmanager.signing.storeFile")?.let(::file)?.takeIf { it.isFile }

/**
 * The app's version: the one line to change for a release. The version code Android compares
 * when updating is derived from it (major * 10000 + minor * 100 + patch), so it always grows
 * with the version: 0.6.0 is 600, 1.2.3 is 10203.
 */
val appVersion = "0.6.0"

fun versionCodeOf(version: String): Int {
    val parts = version.split('.').map(String::toInt)
    require(parts.size == 3 && parts.all { it in 0..99 }) { "Version must be major.minor.patch with parts below 100, got $version" }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "com.llmmanager.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.llmmanager.android"
        minSdk = 26
        targetSdk = 35
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
        val serverUrl = setting("llmmanager.serverUrl").orEmpty()
        require(serverUrl.none { it == '"' || it == '\\' }) { "llmmanager.serverUrl must not contain quotes or backslashes" }
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = setting("llmmanager.signing.storePassword")
                keyAlias = setting("llmmanager.signing.keyAlias")
                keyPassword = setting("llmmanager.signing.keyPassword") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Not minified yet: the app is small and there is no device test to catch R8 regressions.
            isMinifyEnabled = false
            // Without a configured key, fall back to the debug key so local builds still install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // New dependency versions appear constantly; that is Dependabot's job, not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
}
