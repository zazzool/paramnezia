plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing and the pinned OTA key come from Gradle properties or the environment (CI secrets).
fun prop(name: String, env: String): String? = providers.gradleProperty(name).orNull ?: System.getenv(env)
val goAbis = listOf("arm64-v8a", "armeabi-v7a") + if (prop("paramnezia.x86", "PN_X86") == "true") listOf("x86_64") else emptyList()

android {
    namespace = "net.paramnezia.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.paramnezia"
        minSdk = 26
        targetSdk = 36
        versionCode = (prop("paramnezia.versionCode", "PN_VERSION_CODE") ?: "1").toInt()
        versionName = prop("paramnezia.versionName", "PN_VERSION_NAME") ?: "0.1.0"
        // Public half of the release key: only releases signed by it are installed over the air.
        buildConfigField("String", "RELEASE_KEY", "\"${prop("paramnezia.releaseKey", "PN_RELEASE_KEY").orEmpty()}\"")
        buildConfigField("String", "UPDATE_URL", "\"${prop("paramnezia.updateUrl", "PN_UPDATE_URL").orEmpty()}\"")
        // Phones, tablets and TV boxes are ARM (32-bit userland is common on TV boxes). x86_64 is only for
        // emulators: CI's emulator test builds with -Pparamnezia.x86=true; the published APK has no x86.
        ndk { abiFilters += goAbis }
    }

    signingConfigs {
        create("release") {
            val store = prop("paramnezia.keystore", "PN_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = prop("paramnezia.keystorePassword", "PN_KEYSTORE_PASSWORD")
                keyAlias = prop("paramnezia.keyAlias", "PN_KEY_ALIAS") ?: "paramnezia"
                keyPassword = prop("paramnezia.keyPassword", "PN_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (prop("paramnezia.keystore", "PN_KEYSTORE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app speaks Russian (and English for system strings); libraries bring ~80 languages otherwise.
    androidResources { localeFilters += listOf("ru", "en") }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "kotlin/**", "DebugProbesKt.bin")
        // Native libraries stay compressed in the APK: smaller download, extracted once on install.
        jniLibs.useLegacyPackaging = true
    }
}

composeCompiler {
    // The mapping task's class reader stops at Java 24 class files, which some dependencies ship in
    // multi-release jars; the mapping only improves R8-obfuscated Compose stack traces.
    includeComposeMappingFile.set(false)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(project(":deploy"))
    implementation(project(":client"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
