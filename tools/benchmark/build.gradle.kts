// Same Android Gradle Plugin as the app (gradle/libs.versions.toml: agp), so one SDK and one Gradle
// wrapper (the repository's own, via -p) serve both builds.
plugins {
    id("com.android.library") version "9.1.0" apply false
}
