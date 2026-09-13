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
    }
}

rootProject.name = "trailog"

include(":core")
include(":app")

// M2.1, throwaway — see tools/sports-tracker-export/README.md. Included so `./gradlew check`
// compiles it and runs its tests; it is deleted outright once the migration is done.
include(":tools:sports-tracker-export")

// M2.2, durable. The Sports Tracker migration's other half: reads the scraper's output and
// produces a Trailog-canonical archive, parsing every track through :core's single GPX
// implementation so no second parser can ever exist.
include(":tools:importer")
