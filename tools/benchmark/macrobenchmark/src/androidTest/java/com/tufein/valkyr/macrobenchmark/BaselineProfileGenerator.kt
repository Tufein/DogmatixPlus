package com.tufein.valkyr.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tufein.valkyr.macrobenchmark.Target.flingFirstList
import com.tufein.valkyr.macrobenchmark.Target.settle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a baseline profile by launching the installed app and scrolling its first list. The
 * result is a text file of the classes and methods that ran (written to the module's additional
 * test output; docs/PROFILING.md says where and how to merge it into app/src/main/baseline-prof.txt).
 * It needs a device that allows profile collection: Android 13+ (API 33) works without root, older
 * ones need a rooted device or an emulator image with root.
 *
 * Add the screens that matter to you to the block (Library, a game page, Downloads, Tools): only
 * what the generator touches ends up in the profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(
        packageName = Target.packageName,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.settle()
        device.flingFirstList()
    }
}
