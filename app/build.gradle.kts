plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing and the pinned OTA key come from Gradle properties or the environment (CI secrets).
fun prop(name: String, env: String): String? = providers.gradleProperty(name).orNull ?: System.getenv(env)

android {
    namespace = "io.paramnezia.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.paramnezia"
        minSdk = 26
        targetSdk = 36
        versionCode = (prop("paramnezia.versionCode", "PN_VERSION_CODE") ?: "1").toInt()
        versionName = prop("paramnezia.versionName", "PN_VERSION_NAME") ?: "0.1.0"
        // Public half of the release key: only releases signed by it are installed over the air.
        buildConfigField("String", "RELEASE_KEY", "\"${prop("paramnezia.releaseKey", "PN_RELEASE_KEY").orEmpty()}\"")
        buildConfigField("String", "RELEASE_NOSTR", "\"${prop("paramnezia.releaseNostr", "PN_RELEASE_NOSTR").orEmpty()}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "kotlin/**", "DebugProbesKt.bin")
        // Native libraries stay compressed in the APK: smaller download, extracted once on install.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(project(":deploy"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    coreLibraryDesugaring(libs.android.desugar)
}
