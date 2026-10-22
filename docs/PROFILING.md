# Measuring Valkyr: the Performance check and the baseline profile

Two things live here: a tool inside the app that anyone can run on their own handheld (part 1), and a
developer setup that records a real baseline profile and benchmarks start-up and scrolling (part 2).

Nothing in this guide was run on a handheld while it was written. The numbers, thresholds and
commands come from the public Android documentation (FrameMetrics, Macrobenchmark, baseline profiles);
the first run on a real device is the first real test. Where something is a guess it says so.

## 1. The Performance check (in the app)

**Where:** Tools → Performance check (also found by searching for "performance", "speed" or "fps").

**What it does** when you press *Run check* and confirm the preview:

| Part | What is measured | Changes anything? |
| --- | --- | --- |
| Start to first screen | The time PerfLog already recorded from process start to the first frame of this run | No |
| Library queries | Search for a common word, the first page of the list, the count, and the Home shelves' reading of the library, on your real library. First (cold) run and the median of three warm runs | No |
| Cover decode | Up to 24 images from the app's image cache, decoded at list-tile size; median and slowest | No (read only) |
| Free space | Room left on the storage that holds the download folder | No |
| Storage speed | Writes one **hidden 16 MB temporary file** into the download folder, flushes it to the storage, reads it back and deletes it. The file has a random name (`.valkyr-speedtest-<hex>.tmp`) so it can never replace anything; if it cannot be deleted the tool tells you its name | Only that temporary file. Can be switched off. Needs about 80 MB free |
| Frame times | A built-in list of simple rows scrolls by itself for about five seconds while the window's frame times are collected (`Window.addOnFrameMetricsAvailableListener`, the API behind JankStats) | No |

Results come as a number and a word (Fast / Fine / Slow) plus a short explanation. The words use fixed
limits that are rules of thumb for a handheld, not a standard: for example a library search up to
150 ms counts as fast and over 500 ms as slow; a write above 40 MB/s is fast and below 15 MB/s slow;
more than 15% of frames over one refresh interval is slow. The limits are in `util/PerfCheck.kt`
(`verdictOf`) and covered by unit tests. The numbers are always shown next to the word, so you can
judge for yourself.

**Reading the results**

* The *read speed* is measured straight after writing, so the system's cache can make it look faster
  than the storage really is. The write speed (which is flushed to the storage) is the more honest one
  for the question "how fast do my downloads land on this card".
* The *frame times* come from a synthetic list, not from your library. They tell you how well the device
  and the app's drawing keep up, not how a particular screen with covers behaves.
* The first run after installing or a reboot is slower (caches are cold). Run it twice and compare the
  second run.
* An empty library or an empty cover cache skips those parts and says so.

**Sharing the report:** press *Share report* (Y on a gamepad). The text goes to the Android share
sheet, so you choose where it goes; the app sends nothing itself. It contains only numbers and fixed
labels: the app version, Android API level, device model, core count, RAM class and display refresh
rate, the measured values, and the app's own start-up marks, slow calls and cover cache counts. It
holds no folder names, game titles, file names, keys or server addresses, and it also passes through
the same redaction as the Diagnose report. The file is written to the app's private cache
(`exports/valkyr-performance.txt`).

## 2. Baseline profile and Macrobenchmark (developers)

The app already ships a hand-written profile (`app/src/main/baseline-prof.txt`) and includes
`androidx.profileinstaller`, so the profile is read on install. A recorded profile replaces the guess
with what really runs.

### Why a separate build

The benchmark sources are in `tools/benchmark/`, with their own `settings.gradle.kts`. They are **not**
included from the root `settings.gradle.kts`, so `assembleRelease`, `testDebugUnitTest`, `lintDebug`,
`connectedDebugAndroidTest` and the CI workflows (`.github/workflows/test.yml`, `release.yml`) never
see them: those jobs run Gradle tasks by name from the root, and a module that is not in the root
build cannot be reached by them. The benchmark module is a library whose *androidTest* APK
instruments itself and drives the **installed** Valkyr from outside, so it does not need to be part of
the app's build at all. It was written against the AndroidX documentation and **has not been built or
run**; expect to adjust a version or two on first use (`tools/benchmark/macrobenchmark/build.gradle.kts`).

### What you need

* A physical device (an emulator gives meaningless timings and Macrobenchmark refuses it by default).
  Android 13 (API 33) or newer records baseline profiles without root; older versions need root.
  An Ayn Odin or Thor on Android 13+ qualifies.
* USB debugging on, the device visible in `adb devices`, screen on and unlocked.
* The Android SDK (the same one the app builds with) and the repository's Gradle wrapper.
* A **non-debuggable, profileable** Valkyr installed on that device: the normal release APK built
  from the commit you want to measure, signed with any key. Macrobenchmark cannot attach to a
  debuggable build (`.debug` package) with useful results, and a plain release build is not profileable
  by the shell. Make it profileable with one line, in a local change you do not have to commit:

  ```xml
  <!-- app/src/main/AndroidManifest.xml, inside <application ...> -->
  <profileable android:shell="true" />
  ```

  (`profileable` is available from API 29, the app's minSdk. It only lets the `adb shell` user
  profile the app; Google recommends it for measuring release builds. Whether to keep it in the
  shipped manifest is a choice for the maintainer; this guide does not change the manifest.)
  Then `./gradlew assembleRelease` and `adb install -r app/build/outputs/apk/release/app-release.apk`.
* Open the app once and finish the first-start onboarding (the benchmark does not click through it)
  and set up the library you normally use, so the screens have content to scroll.

### Run the benchmarks

From the repository root:

```bash
./gradlew -p tools/benchmark :macrobenchmark:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tufein.valkyr.macrobenchmark.StartupBenchmark
```

Use `ScrollBenchmark` instead of `StartupBenchmark` for frame times, or leave the `class=` argument
out to run everything (the baseline profile generator included). To measure another package, add
`-Pandroid.testInstrumentationRunnerArguments.targetPackage=com.example.other`.

* `StartupBenchmark` measures cold start with no ahead-of-time compilation (the fresh-install worst
  case) and with the baseline profile, plus warm and hot start. The gap between "None" and "Baseline
  profile" is what the profile is worth on that device.
* `ScrollBenchmark` flings the first scrollable list of the first screen and reports frame duration
  percentiles and the count of frames over budget, with and without the profile.
* Results are printed in the test output and written as JSON and traces to
  `tools/benchmark/macrobenchmark/build/outputs/connected_android_test_additional_output/`. A trace
  opens in Android Studio's profiler or at ui.perfetto.dev.

### Record a baseline profile

```bash
./gradlew -p tools/benchmark :macrobenchmark:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tufein.valkyr.macrobenchmark.BaselineProfileGenerator
```

Collecting a profile takes a few minutes: the generator launches the app several times until the
profile is stable. Afterwards look in
`tools/benchmark/macrobenchmark/build/outputs/connected_android_test_additional_output/` for a file
ending in `baseline-prof.txt` (and `startup-prof.txt` when startup profiles are included).

To use it:

1. Open the generated `...-baseline-prof.txt`. It lists rules like `HSPLcom/tufein/valkyr/...;->method(...)V`.
2. Merge it into `app/src/main/baseline-prof.txt`: keep the header comment, add the generated lines
   (the hand-written wildcard lines can stay, a rule that appears twice is harmless) and keep only
   `com.tufein.valkyr` rules (libraries ship their own profiles inside their AARs).
3. Rebuild the release APK and install it. Check that the profile is in: the APK contains
   `assets/dexopt/baseline.prof`, and `adb shell dumpsys package dexopt` shows the app compiled with
   the `speed-profile` filter after a first run and an idle period.
4. Run `StartupBenchmark` again on the new build to compare the numbers with the old profile.

The generator only touches what it opens: the first screen and its first list. Add steps to
`BaselineProfileGenerator.generate()` for the screens you care about (a game page, Downloads, Tools,
Search) and record again, because anything the generator does not exercise is not in the profile.
Everything is driven through the generic UiAutomator API (find a scrollable list, fling it) so it does
not depend on the app's internal ids; a gamepad-only handheld works with that too, but a screen that
needs a D-pad or a specific button needs a small custom step.

### Keep it honest

* Compare like with like: same device, same build, same library, battery above 50% and not hot,
  airplane mode or a quiet network, screen brightness fixed.
* One run is an anecdote. Macrobenchmark repeats each test (8 start-ups, 6 scrolls) and reports the
  median and the range; look at the range before the median.
* Do not turn off the benchmark's own safety checks (emulator, debuggable, low battery) to get a
  number: it would be a wrong number.
