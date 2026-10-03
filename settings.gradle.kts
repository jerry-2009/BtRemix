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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libxposed API 101: compile-time only, the framework provides the runtime classes.
        maven("https://api.xposed.info/")
        // DexKit is also published to JitPack; kept for the M1 host-class lookup tooling.
        maven("https://jitpack.io")
    }
}

rootProject.name = "BtRemix"
include(":app")
