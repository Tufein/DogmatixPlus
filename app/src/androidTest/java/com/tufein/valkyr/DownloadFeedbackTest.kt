package com.tufein.valkyr

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.model.DownloadFailure
import com.tufein.valkyr.data.model.DownloadFailureCategory
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.screens.download.DownloadRow
import com.tufein.valkyr.ui.screens.download.DownloadRowActions
import com.tufein.valkyr.ui.screens.download.QueueHeader
import com.tufein.valkyr.ui.theme.DogmatixTheme
import com.tufein.valkyr.util.QueueActions
import com.tufein.valkyr.util.QueueEta
import com.tufein.valkyr.util.QueueProgress
import com.tufein.valkyr.util.StorageInsights
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
                    summary = QueueProgress.Summary(active = 2, completed = 1, failed = 1, total = 4),
                    eta = QueueEta.Eta(15L * MIB, MIB, null, unknownSizes = 1),
                    need = StorageInsights.QueueNeed(0L, 0L), free = null, shortfall = 0L,
                    waitingReasons = emptyList(), held = false, counts = QueueActions.Counts(),
                    onHold = {}, onStopAll = {}, onRetryFailed = {}, onClearFinished = {}
                )
            }
        }
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.download_queue_unknown_sizes, 1, 1), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.download_eta_unknown), substring = true).assertIsDisplayed()
        compose.onNodeWithText("25%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 1, 4)).assertIsDisplayed()
    }

    @Test fun completingFilesAccumulatesQueueProgressWhileTheNextFileDownloads() {
        val items = mutableStateOf(listOf(
            row().copy(progress = 0.99f, downloadedBytes = 63L * MIB),
            row().copy(name = "Second download.gba", fileName = "Second download.gba", progress = 0f, downloadedBytes = 0L)
        ))
        compose.setContent {
            DogmatixTheme {
                QueueHeader(
                    summary = QueueProgress.of(items.value), eta = QueueEta.ofDownloads(items.value),
                    need = StorageInsights.QueueNeed(0L, 0L), free = null, shortfall = 0L,
                    waitingReasons = emptyList(), held = false, counts = QueueActions.Counts(),
                    onHold = {}, onStopAll = {}, onRetryFailed = {}, onClearFinished = {}
                )
            }
        }
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 0, 2)).assertIsDisplayed()

        compose.runOnIdle {
            items.value = items.value.mapIndexed { index, item ->
                if (index == 0) item.copy(status = DownloadStatus.COMPLETED, progress = 1f, downloadedBytes = item.fileSize)
                else item
            }
        }
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 1, 2)).assertIsDisplayed()

        compose.runOnIdle {
            items.value = items.value.mapIndexed { index, item ->
                if (index == 1) item.copy(progress = 0.25f, downloadedBytes = 16L * MIB)
                else item
            }
        }
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText("0%").assertDoesNotExist()

        compose.runOnIdle {
            items.value = items.value.map { it.copy(status = DownloadStatus.COMPLETED, progress = 1f, downloadedBytes = it.fileSize) }
        }
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 2, 2)).assertIsDisplayed()
    }

    @Test fun idleQueueWithFailedDownloadsShowsActualCompletion() {
        val items = listOf(
            row().copy(status = DownloadStatus.COMPLETED, progress = 1f, downloadedBytes = 64L * MIB),
            row().copy(status = DownloadStatus.FAILED)
        )
        compose.setContent {
            DogmatixTheme {
                QueueHeader(
                    summary = QueueProgress.of(items), eta = QueueEta.ofDownloads(items),
                    need = StorageInsights.QueueNeed(0L, 0L), free = null, shortfall = 0L,
                    waitingReasons = emptyList(), held = false, counts = QueueActions.Counts(),
                    onHold = {}, onStopAll = {}, onRetryFailed = {}, onClearFinished = {}
                )
            }
        }
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_completed_of, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.q5_pill_failed, 1)).assertIsDisplayed()
        compose.onNodeWithText("100%").assertDoesNotExist()
    }

    private fun row() = DownloadItemModel(
        name = "Download test.gba", fileName = "Download test.gba", downloadSpeed = 1f,
        progress = 0.25f, fileSize = 64L * MIB, downloadedBytes = 16L * MIB,
        status = DownloadStatus.DOWNLOADING
    )

    companion object { private const val MIB = 1_048_576L }
}
