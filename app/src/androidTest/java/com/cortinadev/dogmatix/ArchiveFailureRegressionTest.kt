package com.cortinadev.dogmatix

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.service.ArchiveExtractorService
import com.cortinadev.dogmatix.util.DownloadExtractionException
import com.cortinadev.dogmatix.util.DownloadFailures
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFailureRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun strictDownloadExtractionReportsFailureWhileExplorerKeepsItsEmptyResult() = runBlocking {
        val missing = File(context.cacheDir, "missing-archive-${UUID.randomUUID()}.zip")
        val extractor = ArchiveExtractorService()
        assertTrue(extractor.extractArchiveFile(context, missing, Uri.EMPTY).isEmpty())
        try {
            extractor.extractArchiveFile(context, missing, Uri.EMPTY, failOnError = true)
            error("A download must not complete after failed extraction")
        } catch (e: DownloadExtractionException) {
            assertEquals(DownloadFailureCategory.EXTRACTION, DownloadFailures.classify(e)?.category)
        }
    }

    @Test fun stoppingExtractionPropagatesCancellationInsteadOfReturningAnEmptySuccess() = runBlocking {
        val missing = File(context.cacheDir, "canceled-archive-${UUID.randomUUID()}.zip")
        var returned = false
        val task = launch(start = CoroutineStart.LAZY) {
            currentCoroutineContext()[Job]!!.cancel()
            ArchiveExtractorService().extractArchiveFile(context, missing, Uri.EMPTY, failOnError = true)
            returned = true
        }
        task.start()
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
    }
}
