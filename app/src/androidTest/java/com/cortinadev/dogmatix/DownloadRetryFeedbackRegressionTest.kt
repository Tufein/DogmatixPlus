package com.cortinadev.dogmatix

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.model.PendingAutoRetry
import com.cortinadev.dogmatix.ui.screens.download.DownloadRetryFeedback
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadRetryFeedbackRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pendingRetryShowsCountdownAndBothActions() {
        var retried = 0
        var canceled = 0
        val state = mutableStateOf<PendingAutoRetry?>(PendingAutoRetry(2, 3, SystemClock.elapsedRealtime() + 60_000L))
        compose.setContent {
            DogmatixTheme {
                state.value?.let { pending ->
                    DownloadRetryFeedback(pending, { retried++ }, { canceled++; state.value = null })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.retry26_attempt, 2, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry26_now)).performClick()
        compose.runOnIdle { assertEquals(1, retried) }
        compose.onNodeWithText(context.getString(R.string.retry26_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, canceled) }
        compose.onNodeWithText(context.getString(R.string.retry26_attempt, 2, 3)).assertDoesNotExist()
    }

    @Test fun readyTimerHasNoNegativeCountdownAndSelectionModeHidesActions() {
        compose.setContent {
            DogmatixTheme {
                DownloadRetryFeedback(PendingAutoRetry(1, 3, 0L), {}, {}, actionsEnabled = false)
            }
        }
        compose.onNodeWithText(context.getString(R.string.retry26_ready)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry26_now)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.retry26_cancel)).assertDoesNotExist()
    }
}
