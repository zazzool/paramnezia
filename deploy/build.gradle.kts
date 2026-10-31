plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.jsch)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.sshd.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    // The end-to-end deploy test needs a local Docker daemon; enable with PN_E2E=1.
    environment("PN_E2E", System.getenv("PN_E2E") ?: "0")
    testLogging { showStandardStreams = true }
}
