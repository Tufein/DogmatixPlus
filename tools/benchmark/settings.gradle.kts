// A separate Gradle build for the developer-only Macrobenchmark and baseline-profile generator.
// It is NOT included from the repository's root settings.gradle.kts, so `assembleRelease`,
// `testDebugUnitTest`, `connectedDebugAndroidTest` and the CI workflows never see it. Run it on
// purpose, from the repository root: ./gradlew -p tools/benchmark :macrobenchmark:connectedAndroidTest
// (see docs/PROFILING.md). Written from the public AndroidX documentation; not built on a device
// in the repository's own session.
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

rootProject.name = "valkyr-benchmark"
include(":macrobenchmark")
