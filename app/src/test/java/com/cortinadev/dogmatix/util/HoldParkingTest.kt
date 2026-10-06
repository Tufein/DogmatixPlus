package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

class HoldParkingTest {
    private fun row(name: String, status: DownloadStatus = DownloadStatus.DOWNLOADING) =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = 0f, progress = 0f, fileSize = 1L, status = status)

    @Test fun `only transferring rows are parked, waiting ones keep their place and condition`() {
        val list = listOf(row("a"), row("waits-slot"), row("b"), row("waits-condition"), row("c", DownloadStatus.UNZIPPING))
        val names = HoldParking.toPark(list, setOf("a", "b", "c"), { false }, emptySet(), { true })
        assertEquals(listOf("a", "b"), names)
    }

    @Test fun `rows already pausing, already parked or not safe are skipped`() {
        val list = listOf(row("a"), row("b"), row("c"), row("d"))
        val names = HoldParking.toPark(list, setOf("a", "b", "c", "d"), { it == "a" }, setOf("b"), { it != "c" })
        assertEquals(listOf("d"), names)
    }

    @Test fun `a web transfer is resumable only with resume on, a record and ranges`() {
        assertTrue(HoldParking.webResumable(true, true, "bytes", 0L, false))
        assertTrue(HoldParking.webResumable(true, true, null, 0L, false))
        assertTrue(HoldParking.webResumable(true, true, null, 500L, true))
        assertFalse(HoldParking.webResumable(false, true, "bytes", 0L, false))
        assertFalse(HoldParking.webResumable(true, false, "bytes", 0L, false))
        assertFalse(HoldParking.webResumable(true, true, " None ", 0L, false))
        // The server ignored the range of a continued transfer: it has none.
        assertFalse(HoldParking.webResumable(true, true, "bytes", 500L, false))
    }

    @Test fun `requeue keeps list order and only our paused or stopped rows`() {
        val list = listOf(row("c", DownloadStatus.PAUSED), row("x", DownloadStatus.PAUSED), row("a", DownloadStatus.STOPPED),
            row("b", DownloadStatus.DOWNLOADING))
        assertEquals(listOf("c", "a"), HoldParking.toRequeue(list, setOf("a", "b", "c")))
    }

    @Test fun `a pause has landed on paused, stopped or a removed row`() {
        assertTrue(HoldParking.landed(DownloadStatus.PAUSED))
        assertTrue(HoldParking.landed(DownloadStatus.STOPPED))
        assertTrue(HoldParking.landed(null))
        assertFalse(HoldParking.landed(DownloadStatus.DOWNLOADING))
    }

    @Test fun `storage and configuration errors are not the source's fault`() {
        assertFalse(SourceFailures.isSourceSide(StorageException("Failed to create file in storage.")))
        assertFalse(SourceFailures.isSourceSide(SecurityException("no permission")))
        assertFalse(SourceFailures.isSourceSide(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(SourceFailures.isSourceSide(Exception("Incomplete data: disk full, EROFS (Read-only file system)")))
        assertTrue(SourceFailures.isSourceSide(HttpStatusException(503)))
        assertTrue(SourceFailures.isSourceSide(FileNotFoundException("https://example.org/a.zip")))
        assertTrue(SourceFailures.isSourceSide(SocketTimeoutException("timeout")))
        assertTrue(SourceFailures.isSourceSide(null))
    }

    @Test fun `a partial belongs to its own address or a reserve address of the same source`() {
        val mirrors = listOf("https://m1.example/a.zip", "https://example.org/a.zip")
        assertTrue(PartialOwner.sameSource("https://example.org/a.zip", "https://example.org/a.zip", emptyList()))
        assertTrue(PartialOwner.sameSource("https://m1.example/a.zip", "https://example.org/a.zip", mirrors))
        assertFalse(PartialOwner.sameSource("https://other.example/a.zip", "https://example.org/a.zip", mirrors))
    }
}
