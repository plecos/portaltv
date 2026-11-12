import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Google TV sign-in credentials and the release key live in local.properties (never committed).
// CI has no such file and passes the same names as environment variables instead.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String? = localProps.getProperty(name) ?: System.getenv(name)

// Every commit is a new build. versionName is the major.minor in gradle.properties (VERSION_MAJOR_MINOR, changed by
// hand) plus the number of commits after the first, so the first commit is 1.0.0. versionCode is the commit count
// plus VERSION_CODE_OFFSET, which keeps it above builds made before the history was squashed.
// Needs the full git history, so CI checks out with fetch-depth 0.
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1
val majorMinor = providers.gradleProperty("VERSION_MAJOR_MINOR").getOrElse("1.0")
val codeOffset = providers.gradleProperty("VERSION_CODE_OFFSET").getOrElse("0").toInt()

android {
    namespace = "com.portal.tv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.portal.tv"
        minSdk = 34
        targetSdk = 37
        versionCode = commitCount + codeOffset
        versionName = "$majorMinor.${(commitCount - 1).coerceAtLeast(0)}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "GOOGLE_TV_CLIENT_ID",
            "\"${secret("GOOGLE_TV_CLIENT_ID") ?: ""}\""
        )
        buildConfigField(
            "String", "GOOGLE_TV_CLIENT_SECRET",
            "\"${secret("GOOGLE_TV_CLIENT_SECRET") ?: ""}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    // The release key lives outside the project; local.properties says where. Without it the release
    // build still works, but comes out unsigned and can't be installed.
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
            // Shrink and optimize the whole app (no package restriction: limiting R8 to the libraries made
            // it break Kotlin's own classes and crash at startup). Names aren't scrambled, see proguard-rules.pro.
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The weather extension (:weather-extension) ships inside Portal: its APK, built for the same build type, is copied into the
// app's assets as bundled/weather-extension.apk, and Portal installs it at first run (see BundledExtension). It is signed by
// the same key as Portal, which is what lets Portal allow it without asking an adult.
evaluationDependsOn(":weather-extension")

abstract class EmbedWeatherApk : DefaultTask() {
    @get:InputDirectory
    abstract val apkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: throw GradleException("The weather extension's APK wasn't built in ${apkDir.get().asFile}")
        val out = assetsDir.get().asFile.resolve("bundled").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        apk.copyTo(out.resolve("weather-extension.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val ext = project(":weather-extension")
        val embed = tasks.register<EmbedWeatherApk>("embedWeatherExtension$cap") {
            dependsOn(ext.tasks.named("assemble$cap"))
            apkDir.set(ext.layout.buildDirectory.dir("outputs/apk/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embed, EmbedWeatherApk::assetsDir)
    }
}

// Android lint runs from its own tool classpath, which the buildscript constraints in the root build.gradle.kts don't
// reach. It pulls in the same libraries with security advisories, so pin them here too. Build-time only, not in the
// APK. Drop an entry when the Android Gradle plugin ships a version at or above it.
val lintToolFixes = mapOf(
    "com.google.guava:guava" to "33.7.2-jre",
    "org.bouncycastle:bcprov-jdk18on" to "1.85",
    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
)
configurations.configureEach {
    if (name == "androidLintTool") {
        resolutionStrategy.eachDependency {
            lintToolFixes["${requested.group}:${requested.name}"]?.let {
                useVersion(it)
                because("security advisories in the older version the lint tool brings")
            }
        }
    }
}

dependencies {
    implementation(project(":weather-core"))  // the shape of the weather and the protocol to a weather extension, shared with it
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.zxing:core:3.5.4")  // QR code for the Google sign-in dialog
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    // Lets Portal run adb commands on its own TV, to finish setup (Apache-2.0 or GPL-3.0-or-later, see THIRD_PARTY_NOTICES).
    // Its pairing-code support needs an LGPL library (spake2). Portal connects without pairing, so that library is left
    // out; calling the library's pair() would fail.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "com.github.MuntashirAkon.spake2-java")
    }
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")  // makes the certificate adb pairing needs
    implementation("org.conscrypt:conscrypt-android:2.8.0")              // TLS 1.3 with the key export adb pairing needs
    // libadb-android brings in Bouncy Castle 1.81. Keep it at 1.85 or newer, which fixes the Name Constraints, ASN.1 depth
    // and LDAP advisories in older versions.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.85")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814")  // the real org.json, since android.jar's is a stub in unit tests
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}