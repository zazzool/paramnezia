buildscript {
    // AGP comes from Google's Maven; keep it off the classpath when building only the JVM modules.
    if (providers.gradleProperty("paramnezia.jvmOnly").orNull != "true") {
        dependencies { classpath(libs.android.gradle) }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
