package com.tufein.valkyr.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The app under test and the little UI driving the benchmarks share. The package is the installed
 * Valkyr (a release build, or a build that is `profileable`, see docs/PROFILING.md); pass
 * `-Pandroid.testInstrumentationRunnerArguments.targetPackage=...` to measure another one.
 */
internal object Target {
    val packageName: String =
        InstrumentationRegistry.getArguments().getString("targetPackage", "com.tufein.valkyr")

    /** Lets the first screen settle after a launch (the shelves and the list load after the first frame). */
    fun UiDevice.settle() {
        waitForIdle()
        Thread.sleep(1_500)
    }

    /**
     * Flings the first scrollable list of the current screen down and up again, a few times. The
     * app is made for a gamepad, but a fling over the same list exercises the same drawing.
     */
    fun UiDevice.flingFirstList(times: Int = 3) {
        val list = wait(Until.findObject(By.scrollable(true)), 5_000) ?: return
        // Keep away from the screen edges (gesture navigation).
        list.setGestureMargin(displayWidth / 8)
        repeat(times) {
            list.fling(androidx.test.uiautomator.Direction.DOWN)
            waitForIdle()
            list.fling(androidx.test.uiautomator.Direction.UP)
            waitForIdle()
        }
    }
}
