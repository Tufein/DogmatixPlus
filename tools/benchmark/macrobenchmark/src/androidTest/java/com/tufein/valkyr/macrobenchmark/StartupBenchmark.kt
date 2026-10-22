package com.tufein.valkyr.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold, warm and hot start of the installed app, once with no ahead-of-time compilation at all
 * (the worst case: a fresh install before ART has compiled anything) and once with the baseline
 * profile applied (what an installed release does after the profile is read). The difference
 * between the two rows is what the baseline profile is worth on that device.
 *
 * Finish the first-start onboarding in the app before running: the benchmark does not click through it.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldNoCompilation() = start(StartupMode.COLD, CompilationMode.None())

    @Test fun coldBaselineProfile() = start(StartupMode.COLD, CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun warmBaselineProfile() = start(StartupMode.WARM, CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun hotBaselineProfile() = start(StartupMode.HOT, CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: StartupMode, compilation: CompilationMode) = rule.measureRepeated(
        packageName = Target.packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = mode,
        iterations = 8,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }
}
