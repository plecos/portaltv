# Portal release shrinking rules.

# Fragments are recreated by name (after the process is restored), which needs their no-argument
# constructors and class names to survive shrinking.
-keep class com.portal.tv.** extends androidx.fragment.app.Fragment { <init>(); }
-keep class com.portal.tv.** extends androidx.fragment.app.DialogFragment { <init>(); }

# ZXing builds the sign-in QR code; keep it whole rather than risk a missing piece.
-keep class com.google.zxing.** { *; }

# Portal's own adb connection (libadb-android, Conscrypt for TLS 1.3, and the certificate classes) load pieces by
# name and through native code; keep them whole.
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class android.sun.security.** { *; }
# The adb library's pairing code names spake2 classes that Portal leaves out (it never pairs; see build.gradle.kts).
-dontwarn io.github.muntashirakon.crypto.spake2.**
# Conscrypt mentions platform classes from Android versions older than any this app runs on.
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Keep class and method names: a personal app gains little from scrambling them, and crash traces stay readable.
-dontobfuscate

# Line numbers stay in stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
