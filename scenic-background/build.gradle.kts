// Scenic Travel: an animated side-scrolling background for Portal, as a Portal extension. It is a plain Android app with no
// dependencies. Portal binds its background service and shows the picture it draws; see docs/EXTENSIONS.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.getportaltv.scenic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.getportaltv.scenic"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
