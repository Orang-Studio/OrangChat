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
        google()
        mavenCentral()
        // socket.io-client + engine.io-client live on Maven Central; JitPack kept
        // as a fallback for any transitive that is only published there.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OrangChat"
include(":app")
