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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DriveSync"

include(":app")
include(":feature-pairs")
include(":feature-conflicts")
include(":feature-activity")
include(":feature-settings")
include(":core-ui")
include(":core-sync")
include(":core-data")
include(":core-local-storage")
include(":core-drive")
include(":core-work")
