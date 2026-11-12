// Top-level build file where you can add configuration options common to all sub-projects/modules.

// The Android Gradle plugin pulls in a few libraries that have security advisories. These are only used by the build
// on the developer's or CI machine, not shipped in the app, but the fixed releases are drop-in patch updates, so the
// build uses them. Drop an entry when the plugin itself ships a version at or above it.
buildscript {
    dependencies {
        constraints {
            classpath("com.google.guava:guava:33.7.2-jre") { because("deserialization advisory, fixed in 33.7.2") }
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") { because("Name Constraints and ASN.1 advisories, fixed in 1.85") }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85") { because("advisory fixed in 1.84, kept in step with bcprov") }
            classpath("org.bitbucket.b_c:jose4j:0.9.6") { because("compressed JWE denial of service, fixed in 0.9.6") }
            classpath("org.jdom:jdom2:2.0.6.1") { because("XXE advisory, fixed in 2.0.6.1") }
            classpath("org.apache.commons:commons-lang3:3.18.0") { because("uncontrolled recursion advisory, fixed in 3.18.0") }
            classpath("org.apache.httpcomponents:httpclient:4.5.14") { because("XSS advisory, fixed in 4.5.13") }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
