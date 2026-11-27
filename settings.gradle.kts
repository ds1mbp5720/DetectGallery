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

rootProject.name = "DetectGallery"
include(":app")
include(":feature:live")
include(":feature:search")
include(":feature:people")
include(":feature:moment")
include(":core:data")
include(":core:ml")
include(":core:ui")
include(":core:domain")
include(":core:imaging")
