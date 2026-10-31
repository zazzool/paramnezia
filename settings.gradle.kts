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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "paramnezia"

// Pure-Kotlin modules: protocol/config logic, routing lists, server deployment.
// They build and test without the Android SDK. `releasetool` signs and publishes app updates (CI).
include(":core", ":deploy", ":releasetool")

// `-Pparamnezia.jvmOnly=true` skips Android modules (for machines without the Android SDK).
if (providers.gradleProperty("paramnezia.jvmOnly").orNull != "true") {
    include(":tunnel", ":app")
}
