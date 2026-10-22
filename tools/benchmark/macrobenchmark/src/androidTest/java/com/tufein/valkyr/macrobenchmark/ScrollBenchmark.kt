package com.tufein.valkyr.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tufein.valkyr.macrobenchmark.Target.flingFirstList
import com.tufein.valkyr.macrobenchmark.Target.settle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame times (the 50th/90th/95th/99th percentile frame duration and the number of frames over
 * budget) while the first list on the first screen is flung up and down, with and without the
 * baseline profile. It measures the app with the library you already have on the device.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun scrollNoCompilation() = scroll(CompilationMode.None())

    @Test fun scrollBaselineProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scroll(compilation: CompilationMode) = rule.measureRepeated(
        packageName = Target.packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 6,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.settle()
        }
    ) {
        device.flingFirstList()
    }
}
