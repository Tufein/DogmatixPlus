package com.cortinadev.dogmatix

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.PendingAutoRetry
import com.cortinadev.dogmatix.ui.screens.download.DownloadRow
import com.cortinadev.dogmatix.ui.screens.download.DownloadRowActions
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.DownloadSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercise the actual queue row, where status and compact layout determine recovery controls. */
class DownloadRecoveryRowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun item(status: DownloadStatus) = DownloadItemModel(
        "Example", "Example.zip", 0f, 0f, 1024L, status = status
    )

    @Test fun compactRowCanChooseSourceWhileRunningQueuedAndPaused() {
        val state = mutableStateOf(DownloadStatus.DOWNLOADING)
        var choices = 0
        compose.setContent {
            DogmatixTheme {
                DownloadRow(item(state.value), null, true,
                    DownloadRowActions(changeSource = { choices++ }), "",
                    canChangeSource = DownloadSourcePolicy.canChange(state.value))
            }
        }
        listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED).forEach { status ->
            compose.runOnIdle { state.value = status }
            compose.onNodeWithText(context.getString(R.string.source26_choose)).assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(3, choices); state.value = DownloadStatus.COPYING }
        compose.onNodeWithText(context.getString(R.string.source26_choose)).assertDoesNotExist()
    }

    @Test fun failedRowRoutesTimerActionsAndKeepsFeedbackInSelectionMode() {
        val selecting = mutableStateOf(false)
        var retried = 0
        var cancelled = 0
        val failed = item(DownloadStatus.FAILED).copy(
            failure = DownloadFailure(DownloadFailureCategory.HTTP_SERVER, 503),
            failureAt = 1_700_000_000_000L
        )
        compose.setContent {
            DogmatixTheme {
                DownloadRow(failed, null, false,
                    DownloadRowActions(retryNow = { retried++ }, cancelAutomaticRetry = { cancelled++ }), "",
                    selectionMode = selecting.value,
                    pendingRetry = PendingAutoRetry(1, 3, SystemClock.elapsedRealtime() + 60_000L))
            }
        }
        compose.onNodeWithText(context.getString(R.string.retry26_now)).performClick()
        compose.onNodeWithText(context.getString(R.string.retry26_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, retried); assertEquals(1, cancelled); selecting.value = true }
        compose.onNodeWithText(context.getString(R.string.retry26_attempt, 1, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry26_now)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.retry26_cancel)).assertDoesNotExist()
    }
}
