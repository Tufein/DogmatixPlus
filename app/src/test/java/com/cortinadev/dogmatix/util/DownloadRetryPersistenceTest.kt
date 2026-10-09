package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.PendingAutoRetry
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DownloadRetryPersistenceTest {
    @Test fun `timer rounds up fractions without allowing wall-clock changes to affect it`() {
        val pending = PendingAutoRetry(1, 3, 61_000L)
        assertEquals(60L, pending.remainingSeconds(1_000L))
        assertEquals(60L, pending.remainingSeconds(1_001L))
        assertEquals(1L, pending.remainingSeconds(60_999L))
        assertEquals(0L, pending.remainingSeconds(61_000L))
        assertEquals(0L, pending.remainingSeconds(71_000L))
    }

    @Test fun `long timer uses safe ceiling arithmetic`() {
        assertEquals(9_223_372_036_854_776L, PendingAutoRetry(1, 3, Long.MAX_VALUE).remainingSeconds(0L))
    }

    @Test fun `known failure and timestamp survive history restoration`() {
        val restored = history().toItem()
        assertEquals(DownloadFailure(DownloadFailureCategory.HTTP_RATE_LIMITED, 429), restored.failure)
        assertEquals(1_700_000_000_000L, restored.failureAt)
    }

    @Test fun `legacy history has no invented error or timestamp`() {
        val restored = history().copy(failureCategory = null, failureHttpStatusCode = null, failureAt = null).toItem()
        assertNull(restored.failure)
        assertNull(restored.failureAt)
    }

    @Test fun `unknown stored category yields generic help and never exposes stored text`() {
        val restored = history().copy(failureCategory = "https://private.invalid/token=secret", failureHttpStatusCode = Int.MAX_VALUE, failureAt = -3L).toItem()
        assertEquals(DownloadFailure(DownloadFailureCategory.UNKNOWN), restored.failure)
        assertNull(restored.failureAt)
    }

    @Test fun `completed and in-flight rows do not revive old failure metadata`() {
        val completed = history().copy(status = DownloadStatus.COMPLETED.name).toItem()
        assertNull(completed.failure)
        assertNull(completed.failureAt)
        // Process death restores an in-flight row as stopped. Its old diagnostic fields were
        // cleared by retry before it could become in flight; a restored legacy row has none.
        val running = history().copy(status = DownloadStatus.DOWNLOADING.name, failureCategory = null, failureAt = null).toItem()
        assertEquals(DownloadStatus.STOPPED, running.status)
        assertNull(running.failure)
    }

    @Test fun `safe history metadata survives explicit backup roundtrip`() {
        val restored = BackupJson.historyFromJson(BackupJson.historyToJson(listOf(history()))).single().toItem()
        assertEquals(DownloadFailure(DownloadFailureCategory.HTTP_RATE_LIMITED, 429), restored.failure)
        assertEquals(1_700_000_000_000L, restored.failureAt)
    }

    @Test fun `malicious backup error fields are sanitized before persistence`() {
        val json = BackupJson.historyToJson(listOf(history()))
        json[0].asJsonObject.apply {
            addProperty("failureCategory", "server says token=secret")
            addProperty("failureHttpStatusCode", Long.MAX_VALUE)
            addProperty("failureAt", -1L)
        }
        val imported = BackupJson.historyFromJson(JsonParser.parseString(json.toString())).single()
        assertEquals(DownloadFailureCategory.UNKNOWN.name, imported.failureCategory)
        assertNull(imported.failureHttpStatusCode)
        assertNull(imported.failureAt)
        assertFalse(BackupJson.historyToJson(listOf(imported)).toString().contains("token=secret"))
    }

    private fun history(): DownloadHistoryEntity {
        val file = DownloadableFileEntity(name = "Game", fileName = "Game.gba", consoleId = "gba", downloadUrl = "https://example.invalid/Game.gba", fileSize = 100L, fileExtension = "gba")
        val item = DownloadItemModel(file.name, file.fileName, 0f, 0f, file.fileSize, status = DownloadStatus.FAILED, startedAt = 1000L, finishedAt = 1_700_000_000_000L, failure = DownloadFailure(DownloadFailureCategory.HTTP_RATE_LIMITED, 429), failureAt = 1_700_000_000_000L)
        return DownloadHistoryEntity.from(file, item)
    }
}
