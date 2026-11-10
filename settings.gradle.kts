pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android (Portal talking to the TV's own debug service) is only published here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MuntashirAkon") }
        }
    }
}

rootProject.name = "Portal"
include(":app")
include(":sample-extension")
include(":conformance-extension")
