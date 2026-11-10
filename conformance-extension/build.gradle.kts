// A deliberately misbehaving Portal extension, for scripts/conformance.sh. It asks for everything, then tries calls it
// shouldn't be able to make (other apps' reminders, Portal's own buttons, spoofed names, bad and oversized values) and
// reports whether each was refused. It is a test tool: never part of Portal and never released.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.getportaltv.rogue"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.getportaltv.rogue"
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
