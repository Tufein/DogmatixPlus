package com.cortinadev.dogmatix

import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.UpdateInstaller
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Uses Android's real APK parser; none of these checks creates an installation session. */
class UpdatePreflightRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val installer get() = UpdateInstaller(context)
    private val currentApk get() = File(context.applicationInfo.sourceDir)

    @Test fun alreadyInstalledApkIsNotOfferedAgain() {
        assertEquals(context.getString(R.string.update_not_newer),
            installer.preflight(currentApk, "v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", BuildConfig.VERSION_CODE.toLong()))
    }

    @Test fun releaseTagAndBuildMetadataMustMatchTheActualApk() {
        assertEquals(context.getString(R.string.update_invalid_apk),
            installer.preflight(currentApk, "v99.0.0", BuildConfig.VERSION_CODE.toLong()))
        assertEquals(context.getString(R.string.update_invalid_apk),
            installer.preflight(currentApk, "v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", BuildConfig.VERSION_CODE.toLong() + 1))
    }

    @Test fun otherPackageAndMissingApkAreRefused() {
        assertEquals(context.getString(R.string.update_wrong_package),
            installer.preflight(File(instrumentation.context.applicationInfo.sourceDir), "v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", null))
        assertEquals(context.getString(R.string.update_invalid_apk),
            installer.preflight(File(context.cacheDir, "missing-update.apk"), "v99.0.0", null))
    }
}
