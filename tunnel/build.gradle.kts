plugins {
    id("com.android.library")
}

val goAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val minSdkVersion = 26
// Go output lands in the default jniLibs folder (git-ignored), so no source set wiring is needed.
val goOut = layout.projectDirectory.dir("src/main/jniLibs")

android {
    namespace = "io.paramnezia.tunnel"
    compileSdk = 36

    defaultConfig {
        minSdk = minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += goAbis }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// amneziawg-go is built with the NDK's clang through a small script; see build-go.sh.
val buildGo = tasks.register<Exec>("buildGo") {
    group = "build"
    description = "Builds libparamnezia.so (amneziawg-go) for $goAbis"
    inputs.dir("src/main/go")
    inputs.file("build-go.sh")
    outputs.dir(goOut)
    commandLine(listOf("bash", file("build-go.sh").path, goOut.get().asFile.path, minSdkVersion.toString()) + goAbis)
}

tasks.named("preBuild") { dependsOn(buildGo) }

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
