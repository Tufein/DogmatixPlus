package com.cortinadev.dogmatix

import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepositoryImpl
import com.cortinadev.dogmatix.data.service.NotificationActionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real failure -> timer -> user action, against a loopback HTTP server and an isolated SAF folder. */
class AutomaticRetryServiceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun timersCanBeCanceledRetriedNowAndDisabledWithoutRevivingSupersededRows() = runBlocking {
        val downloads = EntryPointAccessors.fromApplication(context.applicationContext, NotificationActionEntryPoint::class.java).downloads()
        val settings = AppSettings(context)
        val repository = SettingsRepositoryImpl(SettingsDataStore(context))
        val autoBefore = settings.autoRetryFailed.first()
        val heldBefore = settings.queueHeld.first()
        val freeBefore = settings.minFreeGb.first()
        val wifiBefore = repository.downloadWifiOnly.first()
        val chargingBefore = repository.downloadChargingOnly.first()
        val nightBefore = repository.downloadNightOnly.first()
        val slotsBefore = repository.concurrentDownloads.first()
        val console = "retry-${UUID.randomUUID()}"
        val baseTree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val root = requireNotNull(DocumentFile.fromTreeUri(context, baseTree)?.createDirectory(console))
        val tree = DocumentsContract.buildTreeDocumentUri(baseTree.authority, DocumentsContract.getDocumentId(root.uri))
        val names = (0 until 3).map { "$console-$it.gba" }
        try {
            repository.updateConsoleDownloadDirectory(console, tree.toString())
            repository.setConcurrentDownloads(3)
            repository.setDownloadWifiOnly(false)
            repository.setDownloadChargingOnly(false)
            repository.setDownloadNightOnly(false)
            settings.setMinFreeGb(0)
            settings.setAutoRetryFailed(true)
            downloads.gate.setHeld(false)
            eventually { !downloads.gate.held.value && downloads.gate.waiting.value.isEmpty() }
            BusyServer().use { server ->
                val files = names.map { name -> DownloadableFileEntity(name = name, fileName = name, consoleId = console, downloadUrl = server.url, fileSize = 10L, fileExtension = "gba") }
                downloads.startDownloads(files)
                eventually(30_000L) { downloads.pendingAutoRetries.value.keys.containsAll(names) }
                names.forEach { name ->
                    val pending = requireNotNull(downloads.pendingAutoRetries.value[name])
                    assertEquals(1, pending.attempt)
                    assertEquals(3, pending.maxAttempts)
                    assertTrue(pending.remainingSeconds(SystemClock.elapsedRealtime()) in 1L..60L)
                    val row = downloads.downloads.value.first { it.fileName == name }
                    assertEquals(DownloadFailureCategory.HTTP_SERVER, row.failure?.category)
                    assertEquals(503, row.failure?.httpStatusCode)
                    assertNotNull(row.failureAt)
                }

                downloads.cancelAutomaticRetry(names[0])
                eventually { names[0] !in downloads.pendingAutoRetries.value }
                assertEquals(DownloadStatus.FAILED, downloads.downloads.value.first { it.fileName == names[0] }.status)
                assertNotNull(downloads.downloads.value.first { it.fileName == names[0] }.failure)
                downloads.retryNow(names[0]) // An event from a dismissed timer must do nothing.
                assertEquals(DownloadStatus.FAILED, downloads.downloads.value.first { it.fileName == names[0] }.status)

                downloads.gate.setHeld(true)
                eventually { downloads.gate.held.value }
                downloads.retryNow(names[1])
                eventually { names[1] !in downloads.pendingAutoRetries.value && names[1] in downloads.waitingFiles.value }
                val fresh = downloads.downloads.value.first { it.fileName == names[1] }
                assertEquals(DownloadStatus.DOWNLOADING, fresh.status)
                assertNull(fresh.failure)
                assertNull(fresh.failureAt)

                settings.setAutoRetryFailed(false)
                eventually { downloads.pendingAutoRetries.value.keys.none { it in names } }
                val requests = server.requests.get()
                delay(300L)
                assertEquals(requests, server.requests.get())
                assertEquals(DownloadStatus.FAILED, downloads.downloads.value.first { it.fileName == names[2] }.status)

                // Global Stop also includes failed rows waiting on a timer, so they cannot
                // spring back to life after the user has stopped the queue.
                settings.setAutoRetryFailed(true)
                downloads.retryDownload(names[2])
                downloads.gate.setHeld(false)
                eventually(30_000L) { names[2] in downloads.pendingAutoRetries.value }
                downloads.cancelAllDownloads()
                eventually { downloads.pendingAutoRetries.value.keys.none { it in names } }
                val globallyCanceled = downloads.downloads.value.first { it.fileName == names[2] }
                assertEquals(DownloadStatus.FAILED, globallyCanceled.status)
                assertNotNull(globallyCanceled.failure)
                assertNotNull(globallyCanceled.failureAt)
            }
        } finally {
            names.forEach { downloads.deleteDownload(it, deleteFile = false) }
            eventually { downloads.downloads.value.none { it.fileName in names } }
            repository.updateConsoleDownloadDirectory(console, "")
            repository.setConcurrentDownloads(slotsBefore)
            repository.setDownloadWifiOnly(wifiBefore)
            repository.setDownloadChargingOnly(chargingBefore)
            repository.setDownloadNightOnly(nightBefore)
            settings.setMinFreeGb(freeBefore)
            settings.setAutoRetryFailed(autoBefore)
            downloads.gate.setHeld(heldBefore)
            eventually { downloads.gate.held.value == heldBefore }
            root.delete()
        }
    }

    private suspend fun eventually(timeout: Long = 5_000L, test: () -> Boolean) = withTimeout(timeout) {
        while (!test()) delay(20L)
    }

    private class BusyServer : Closeable {
        private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${listener.localPort}/game.gba"
        private val worker = Thread {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    active.set(socket)
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (!input.readLine().isNullOrEmpty()) { /* Consume only request headers. */ }
                    requests.incrementAndGet()
                    socket.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    active.set(null)
                }
            } catch (e: Exception) { if (!listener.isClosed) failure.set(e) }
        }.apply { name = "retry-service-http"; isDaemon = true; start() }
        override fun close() {
            listener.close()
            active.getAndSet(null)?.close()
            worker.join(3_000L)
            assertFalse(worker.isAlive)
            assertNull("Local retry fixture failed: ${failure.get()}", failure.get())
        }
    }
}
