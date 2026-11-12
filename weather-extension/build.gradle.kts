// Weather for Portal, as an extension: the forecast from Open-Meteo, US alerts from the National Weather Service and city search.
// Portal ships this inside its own APK and installs it at first run, and it updates through the catalog like any extension.
// Portal itself fetches no weather; it asks this (or whichever source an adult chose) over the protocol in :weather-core.
//
// It must be signed with Portal's release key: its package name is reserved for Portal's own apps, and Portal only allows it
// without asking an adult when it is signed by the same key as Portal itself.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String? = localProps.getProperty(name) ?: System.getenv(name)

// Every commit is a new build, numbered like Portal's own so a newer one always replaces an older one (see app/build.gradle.kts).
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1
val majorMinor = providers.gradleProperty("VERSION_MAJOR_MINOR").getOrElse("1.0")
val codeOffset = providers.gradleProperty("VERSION_CODE_OFFSET").getOrElse("0").toInt()

android {
    namespace = "net.getportaltv.weather"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.getportaltv.weather"
        minSdk = 34
        targetSdk = 37
        versionCode = commitCount + codeOffset
        versionName = "$majorMinor.${(commitCount - 1).coerceAtLeast(0)}"
    }

    buildFeatures {
        buildConfig = true
    }

    val releaseStore = secret("RELEASE_STORE_FILE")?.let { rootProject.file(it).takeIf { f -> f.exists() } }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":weather-core"))
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814")  // the real org.json, since android.jar's is a stub in unit tests
}
