pluginManagement {
    includeBuild("build-logic")
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
        // JitPack only serves the RootEncoder / RTSP-Server artifacts, nothing else.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeGroup("com.github.pedroSG94.RootEncoder")
            }
        }
    }
}

rootProject.name = "CamNode"
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:control")
include(":core:network")
include(":core:data")
include(":feature:household")
include(":feature:alerts")
include(":feature:applock")
include(":feature:node")
include(":feature:viewer")
include(":recorder")
include(":third_party:rtspserver")
