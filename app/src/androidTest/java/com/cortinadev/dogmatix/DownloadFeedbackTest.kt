package com.cortinadev.dogmatix

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
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.download.DownloadRow
import com.cortinadev.dogmatix.ui.screens.download.DownloadRowActions
import com.cortinadev.dogmatix.ui.screens.download.QueueHeader
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.QueueActions
import com.cortinadev.dogmatix.util.QueueEta
import com.cortinadev.dogmatix.util.QueueProgress
import com.cortinadev.dogmatix.util.StorageInsights
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** What people see and can do, including parked rows and incomplete size information. */
class DownloadFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun liveFileShowsSpeedAndOwnEstimateAndPauseRemovesBoth() {
        val item = mutableStateOf(row())
        compose.setContent { DogmatixTheme { DownloadRow(item.value, null, false, DownloadRowActions(), "") } }
        val speed = context.getString(R.string.q5_per_second, formatBytes(MIB))
        val estimate = context.getString(R.string.download_time_left, context.getString(R.string.downloads_eta_minutes, 1L))
        compose.onNodeWithText(speed, substring = true).assertIsDisplayed()
        compose.onNodeWithText(estimate, substring = true).assertIsDisplayed()
        compose.runOnIdle { item.value = item.value.copy(status = DownloadStatus.PAUSED) }
        compose.onNodeWithText(speed, substring = true).assertDoesNotExist()
        compose.onNodeWithText(estimate, substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.status_paused)).assertIsDisplayed()
    }

    @Test fun unknownSizeShowsBytesAndDoesNotInventTimeRemaining() {
        compose.setContent { DogmatixTheme { DownloadRow(row().copy(fileSize = 0L), null, false, DownloadRowActions(), "") } }
        compose.onNodeWithText(context.getString(R.string.download_bytes_unknown_total, formatBytes(16L * MIB)), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_eta_unknown), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_time_left, context.getString(R.string.downloads_eta_minutes, 1L)), substring = true).assertDoesNotExist()
    }

    @Test fun queuedFileDoesNotShowStaleTransferSpeed() {
        compose.setContent {
            DogmatixTheme { DownloadRow(row(), null, false, DownloadRowActions(), "", queuePosition = 7) }
        }
        compose.onNodeWithText(context.getString(R.string.status_in_queue, 7)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_per_second, formatBytes(MIB)), substring = true).assertDoesNotExist()
    }

    @Test fun unavailableFolderOffersSettingsInsteadOfBlindRetry() {
        var openedSettings = 0
        var retried = 0
        compose.setContent {
            DogmatixTheme {
                DownloadRow(
                    row().copy(status = DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureCategory.STORAGE_PERMISSION)),
                    null, false, DownloadRowActions(openSettings = { openedSettings++ }, retry = { retried++ }), ""
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.download_error_storage_permission)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_fix_folder)).performClick()
        compose.runOnIdle {
            assertEquals(1, openedSettings)
            assertEquals(0, retried)
        }
    }

    @Test fun lowSpaceStopKeepsItsExplanationAndOpensStorage() {
        var openedStorage = 0
        compose.setContent {
            DogmatixTheme {
                DownloadRow(
                    row().copy(status = DownloadStatus.STOPPED, failure = DownloadFailure(DownloadFailureCategory.STORAGE_FULL)),
                    null, false, DownloadRowActions(openStorage = { openedStorage++ }), ""
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.download_error_storage_full)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_free_storage)).performClick()
        compose.runOnIdle { assertEquals(1, openedStorage) }
    }

    @Test fun missingSourceShowsHttpCodeAndOffersSourceManagement() {
        var openedSources = 0
        compose.setContent {
            DogmatixTheme {
                DownloadRow(
                    row().copy(status = DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureCategory.HTTP_NOT_FOUND, 404)),
                    null, false, DownloadRowActions(openSources = { openedSources++ }), ""
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.download_error_not_found), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_http_status, 404), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_check_sources)).performClick()
        compose.runOnIdle { assertEquals(1, openedSources) }
    }

    @Test fun restoredFailureWithoutReasonHasUsefulFallbackAndRetry() {
        var retried = 0
        compose.setContent {
            DogmatixTheme {
                DownloadRow(row().copy(status = DownloadStatus.FAILED), null, false, DownloadRowActions(retry = { retried++ }), "")
            }
        }
        compose.onNodeWithText(context.getString(R.string.download_error_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun aggregateEtaExplainsUnknownSizedFiles() {
        compose.setContent {
            DogmatixTheme {
                QueueHeader(
                    summary = QueueProgress.Summary(active = 2, doneBytes = MIB, totalBytes = 16L * MIB),
                    eta = QueueEta.Eta(15L * MIB, MIB, null, unknownSizes = 1),
                    need = StorageInsights.QueueNeed(0L, 0L), free = null, shortfall = 0L,
                    waitingReasons = emptyList(), held = false, counts = QueueActions.Counts(),
                    onHold = {}, onStopAll = {}, onRetryFailed = {}, onClearFinished = {}
                )
            }
        }
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.download_queue_unknown_sizes, 1, 1), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_eta_unknown), substring = true).assertIsDisplayed()
        compose.onNodeWithText("6%").assertDoesNotExist()
    }

    private fun row() = DownloadItemModel(
        name = "Download test.gba", fileName = "Download test.gba", downloadSpeed = 1f,
        progress = 0.25f, fileSize = 64L * MIB, downloadedBytes = 16L * MIB,
        status = DownloadStatus.DOWNLOADING
    )

    companion object { private const val MIB = 1_048_576L }
}
