import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Paramnezia for Windows: the shared client logic (:client), the Android app's screens compiled
// for the desktop, and a small Windows layer (service pipe, DPAPI state, tray). See docs/WINDOWS.md.

fun prop(name: String, env: String): String? = providers.gradleProperty(name).orNull ?: System.getenv(env)
val versionName = prop("paramnezia.versionName", "PN_VERSION_NAME")?.removePrefix("v") ?: "0.1.0"
val versionCode = prop("paramnezia.versionCode", "PN_VERSION_CODE") ?: "1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The Android app's screens, compiled here unchanged (they reach the system only through UiPlatform).
val sharedUiFiles = listOf(
    "UiPlatform.kt", "Theme.kt", "PowerButton.kt", "HomeKit.kt", "HomeParts.kt", "HomeScreen.kt", "SimpleScreen.kt",
    "FormKit.kt", "SshForm.kt", "AddScreen.kt", "CloudDialog.kt", "FleetScreen.kt", "Qr.kt", "Shell.kt", "Orbit.kt", "LanScreens.kt",
)
val syncSharedUi by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/kotlin/net/paramnezia/app/ui")) { include(sharedUiFiles) }
    into(layout.buildDirectory.dir("generated/sharedUi/net/paramnezia/app/ui"))
}

// BuildConfig with the same names the shared screens read on Android.
val buildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildConfig")
    val releaseKey = prop("paramnezia.releaseKey", "PN_RELEASE_KEY").orEmpty()
    val updateUrl = prop("paramnezia.updateUrl", "PN_UPDATE_URL").orEmpty()
    inputs.property("v", listOf(versionName, versionCode, releaseKey, updateUrl))
    outputs.dir(out)
    doLast {
        val f = out.get().file("net/paramnezia/app/BuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("""
            |package net.paramnezia.app
            |
            |object BuildConfig {
            |    const val VERSION_NAME = "$versionName"
            |    const val VERSION_CODE = ${versionCode}L
            |    const val RELEASE_KEY = "$releaseKey"
            |    /** Signed release-windows.json next to the Android release.json. */
            |    const val UPDATE_URL = "${updateUrl.replace("release.json", "release-windows.json")}"
            |    const val ANDROID_UPDATE_URL = "$updateUrl"
            |}
            |""".trimMargin())
    }
}

sourceSets {
    main {
        kotlin.srcDir(syncSharedUi.map { layout.buildDirectory.dir("generated/sharedUi").get() })
        kotlin.srcDir(buildConfig)
        // Bypass lists and DNS rules: the very files the Android app ships.
        resources.srcDir(rootProject.file("app/src/main/assets"))
        resources.srcDir("icons")
    }
}

dependencies {
    implementation(project(":client"))
    implementation(compose.desktop.currentOs)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(libs.cmp.icons.core)
    implementation(libs.zxing.core)
    implementation(libs.jna.platform)
    implementation(libs.jmdns)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "net.paramnezia.desktop.MainKt"
        jvmArgs += listOf("-Xmx256m", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            // The installer is built by Inno Setup (desktop/installer/paramnezia.iss); here only the app image.
            targetFormats(TargetFormat.Msi)
            packageName = "Paramnezia"
            packageVersion = versionName.substringBefore('-').let { v -> if (v.count { it == '.' } == 2) v else "$v.0" }
            description = "Paramnezia VPN"
            vendor = "Paramnezia"
            copyright = "Paramnezia"
            // jdk.localedata: country names in Russian; jdk.crypto.ec: SSH key exchange; java.naming: JSch.
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "jdk.localedata", "jdk.charsets", "java.management")
            windows {
                iconFile.set(project.file("icons/paramnezia.ico"))
                menuGroup = "Paramnezia"
                upgradeUuid = "6f0d6c6e-7061-726d-6e65-7a6961000001"
            }
        }
    }
}

tasks.register<JavaExec>("screenshots") {
    description = "Renders the screens with sample data into build/screenshots (no window, no service)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.paramnezia.desktop.MainKt")
    args("--screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
}
