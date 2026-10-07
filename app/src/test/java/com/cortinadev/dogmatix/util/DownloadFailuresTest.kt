package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.service.DebridAuthException
import com.cortinadev.dogmatix.data.service.HttpStatusException
import com.cortinadev.dogmatix.data.service.LowStorageException
import com.cortinadev.dogmatix.data.service.TorrentMetadataTimeoutException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFailuresTest {
    @Test fun `HTTP reasons preserve code and choose the useful remedy`() {
        val cases = mapOf(
            401 to DownloadFailureCategory.HTTP_AUTHENTICATION,
            403 to DownloadFailureCategory.HTTP_AUTHENTICATION,
            404 to DownloadFailureCategory.HTTP_NOT_FOUND,
            410 to DownloadFailureCategory.HTTP_NOT_FOUND,
            408 to DownloadFailureCategory.TIMEOUT,
            429 to DownloadFailureCategory.HTTP_RATE_LIMITED,
            500 to DownloadFailureCategory.HTTP_SERVER,
            503 to DownloadFailureCategory.HTTP_SERVER,
            504 to DownloadFailureCategory.TIMEOUT,
            416 to DownloadFailureCategory.HTTP_OTHER
        )
        cases.forEach { (code, expected) ->
            val result = DownloadFailures.classify(HttpStatusException(code))!!
            assertEquals(expected, result.category)
            assertEquals(code, result.httpStatusCode)
        }
    }

    @Test fun `HTTP code survives an intermediate IO wrapper`() {
        val failure = DownloadFailures.classify(IOException("request failed", HttpStatusException(429)))!!
        assertEquals(DownloadFailureCategory.HTTP_RATE_LIMITED, failure.category)
        assertEquals(429, failure.httpStatusCode)
    }

    @Test fun `quota and full disk wrapped by storage and archive errors remain storage full`() {
        for (marker in listOf("ENOSPC", "No space left on device", "EDQUOT", "Disk quota exceeded", "Disk full")) {
            val cause = DownloadExtractionException(StorageException("write failed", IOException(marker)))
            assertEquals(DownloadFailureCategory.STORAGE_FULL, DownloadFailures.classify(cause)?.category)
        }
        assertEquals(DownloadFailureCategory.STORAGE_FULL, DownloadFailures.classify(LowStorageException("game.zip"))?.category)
    }

    @Test fun `lost folder permission and read only volume suggest selecting storage`() {
        assertEquals(DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailures.classify(StorageAccessException())?.category)
        assertEquals(DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailures.classify(StorageException("failed", SecurityException("provider denied")))?.category)
        assertEquals(DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailures.classify(IOException("EROFS"))?.category)
        assertEquals(DownloadFailureCategory.STORAGE_WRITE, DownloadFailures.classify(StorageException("provider write failed"))?.category)
    }

    @Test fun `network failures and metadata timeouts offer a retry`() {
        assertEquals(DownloadFailureCategory.NETWORK, DownloadFailures.classify(UnknownHostException("private.host"))?.category)
        assertEquals(DownloadFailureCategory.TIMEOUT, DownloadFailures.classify(SocketTimeoutException())?.category)
        assertEquals(DownloadFailureCategory.TIMEOUT, DownloadFailures.classify(TorrentMetadataTimeoutException("private magnet"))?.category)
    }

    @Test fun `debrid authentication is not a network problem`() {
        assertEquals(DownloadFailureCategory.HTTP_AUTHENTICATION, DownloadFailures.classify(DebridAuthException("private response"))?.category)
    }

    @Test fun `archive corruption is separate from network failure`() {
        val failedArchive = DownloadExtractionException(IOException("damaged archive"))
        assertEquals(DownloadFailureCategory.EXTRACTION, DownloadFailures.classify(failedArchive)?.category)
    }

    @Test fun `nested extraction storage errors never trigger automatic source switching`() {
        assertFalse(SourceFailures.isSourceSide(DownloadExtractionException(StorageException("provider failure"))))
        assertFalse(SourceFailures.isSourceSide(DownloadExtractionException(IOException("ENOSPC"))))
    }

    @Test fun `user cancellation even when wrapped never becomes an error message`() {
        assertNull(DownloadFailures.classify(CancellationException("paused")))
        assertNull(DownloadFailures.classify(DownloadExtractionException(CancellationException("stopped"))))
    }

    @Test fun `native disk alerts retain the correct device remedy without provider text`() {
        assertEquals(DownloadFailureCategory.STORAGE_FULL, DownloadFailures.torrentAlert("/private/game: ENOSPC", true).category)
        assertEquals(DownloadFailureCategory.STORAGE_PERMISSION, DownloadFailures.torrentAlert("Permission denied for /private/game", true).category)
        assertEquals(DownloadFailureCategory.STORAGE_WRITE, DownloadFailures.torrentAlert("provider file error", true).category)
        assertEquals(DownloadFailureCategory.TORRENT, DownloadFailures.torrentAlert("peer problem", false).category)
    }

    @Test fun `safe summary never retains exception details or credentials`() {
        val privateUrl = "https://account:password@private.host/file?token=secret"
        val failure = DownloadFailures.classify(IOException(privateUrl))!!
        assertEquals(DownloadFailureCategory.NETWORK, failure.category)
        assertNull(failure.httpStatusCode)
        assertFalse(failure.toString().contains(privateUrl))
        assertFalse(failure.toString().contains("password"))
        assertFalse(failure.toString().contains("secret"))
    }
}
