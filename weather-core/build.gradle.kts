// What Portal and a weather extension share: the shape of the weather (report, alerts, cities), the JSON it travels in and the
// constants of the question-and-answer protocol. No weather is fetched here; that is the extension's job (weather-extension),
// and Portal only ever reads what an extension sends back (see WeatherValidator in the app).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.getportaltv.weather.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814")  // the real org.json, since android.jar's is a stub in unit tests
}
