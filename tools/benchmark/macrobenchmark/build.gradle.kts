plugins {
    id("com.android.library")
}

// The benchmarks live in this module's androidTest source set: the test APK instruments itself and
// drives the already installed Valkyr (a release or a profileable build) from the outside, which is
// what Macrobenchmark needs (it must never run inside the process it measures).
android {
    namespace = "com.tufein.valkyr.macrobenchmark"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // Macrobenchmark needs 23+, baseline profile generation 28+; the app's own minSdk is 29.
        minSdk = 29
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Without a rooted device the benchmark refuses an emulator and a debuggable target; the
        // checks stay on (never silence them to get a number: it would be a wrong number).
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.4.0")
}
