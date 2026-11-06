// A small, working Portal extension: the example for docs/EXTENSIONS.md, and what Portal's own tests install.
// It is a plain Android app with no dependencies; the only thing that makes it an extension is res/xml/portal_extension.xml
// and the meta-data entry in its manifest.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.getportaltv.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.getportaltv.sample"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
