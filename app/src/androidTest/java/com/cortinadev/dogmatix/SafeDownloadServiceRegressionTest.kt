package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepositoryImpl
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.NotificationActionEntryPoint
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.VerifiedCopy
import com.cortinadev.dogmatix.util.VerifyState
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Loopback HTTP -> real worker -> verified SAF commit, including pause/range and source checksum. */
class SafeDownloadServiceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sha(body: String) = VerifiedCopy.hash(body.byteInputStream())

    private fun fixture(test: suspend (DownloadService, DocumentFile, String, String) -> Unit) = runBlocking {
        val downloads = EntryPointAccessors.fromApplication(context.applicationContext, NotificationActionEntryPoint::class.java).downloads()
        val settings = AppSettings(context)
        val repository = SettingsRepositoryImpl(SettingsDataStore(context))
        val autoBefore = settings.autoRetryFailed.first()
        val heldBefore = settings.queueHeld.first()
        val freeBefore = settings.minFreeGb.first()
        val resumeBefore = settings.resumeDownloads.first()
        val wifiBefore = repository.downloadWifiOnly.first()
        val chargingBefore = repository.downloadChargingOnly.first()
        val nightBefore = repository.downloadNightOnly.first()
        val unzipBefore = repository.autoUnzip.first()
        val console = "safe-http-${UUID.randomUUID()}"
        val base = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val root = requireNotNull(DocumentFile.fromTreeUri(context, base)?.createDirectory(console))
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority, DocumentsContract.getDocumentId(root.uri))
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val fileName = "nested/C++%20$console.gba"
        try {
            testContext.grantUriPermission(context.packageName, tree, flags)
            repository.updateConsoleDownloadDirectory(console, tree.toString())
            repository.setDownloadWifiOnly(false); repository.setDownloadChargingOnly(false); repository.setDownloadNightOnly(false)
            repository.setAutoUnzip(false)
            settings.setMinFreeGb(0); settings.setAutoRetryFailed(false); settings.setResumeDownloads(true)
            downloads.gate.setHeld(false)
            eventually { !downloads.gate.held.value }
            test(downloads, root, console, fileName)
        } finally {
            try {
                downloads.deleteDownload(fileName, deleteFile = false)
                eventually { downloads.downloads.value.none { it.fileName == fileName } }
                repository.updateConsoleDownloadDirectory(console, "")
                repository.setDownloadWifiOnly(wifiBefore); repository.setDownloadChargingOnly(chargingBefore); repository.setDownloadNightOnly(nightBefore)
                repository.setAutoUnzip(unzipBefore)
                settings.setMinFreeGb(freeBefore); settings.setAutoRetryFailed(autoBefore); settings.setResumeDownloads(resumeBefore)
                downloads.gate.setHeld(heldBefore)
                eventually { downloads.gate.held.value == heldBefore }
                context.contentResolver.call(base, "fixture:clear_faults", null, null)
                root.delete()
            } finally { testContext.revokeUriPermission(tree, flags) }
        }
    }

    private suspend fun eventually(timeout: Long = 30_000, test: () -> Boolean) = withTimeout(timeout) { while (!test()) delay(20) }
    private fun file(console: String, name: String, server: Server, hash: String?) = DownloadableFileEntity(consoleId = console,
        name = "Game", fileName = name, downloadUrl = server.url, fileSize = 10, fileExtension = ".gba", expectedHash = hash?.let { "sha256:$it" })
    private fun read(root: DocumentFile, name: String) = StorageHelper.readText(context, requireNotNull(root.findFile(name)))

    @Test fun pauseAndRangeResumePreserveCompletedGameUntilTheReplacementIsVerified() = fixture { downloads, root, console, name ->
        val storedName = "C++ $console.gba"
        StorageHelper.writeTextSafely(context, root, "", storedName, "old completed game")
        Server("abcdefghij", holdFirstBody = true).use { server ->
            val row = file(console, name, server, sha("abcdefghij"))
            val manager = EntryPointAccessors.fromApplication(context.applicationContext, StorageSafetyEntryPoint::class.java).fileManager()
            downloads.startDownload(row)
            eventually { root.findFile(manager.stagingName(row))?.length() == 4L }
            assertEquals("old completed game", read(root, storedName))
            downloads.pauseDownload(name)
            server.releaseFirst.countDown()
            eventually { downloads.downloads.value.firstOrNull { it.fileName == name }?.status == DownloadStatus.PAUSED }
            assertEquals("old completed game", read(root, storedName))
            downloads.retryDownload(name)
            eventually { downloads.downloads.value.firstOrNull { it.fileName == name }?.status == DownloadStatus.COMPLETED }
            assertEquals("abcdefghij", read(root, storedName))
            assertTrue(server.ranges.contains("bytes=4-"))
            assertNull(root.findFile(manager.stagingName(row)))
            assertEquals(listOf(storedName), downloads.uploadCandidates(name))
            assertNotNull(downloads.openIntentFor(name))
        }
    }

    @Test fun wrongSourceChecksumCannotReplaceTheCompletedGameOrEnterAutomaticRetry() = fixture { downloads, root, console, name ->
        val storedName = "C++ $console.gba"
        StorageHelper.writeTextSafely(context, root, "", storedName, "old completed game")
        Server("wrong data").use { server ->
            downloads.startDownload(file(console, name, server, sha("abcdefghij")))
            eventually { downloads.downloads.value.firstOrNull { it.fileName == name }?.status == DownloadStatus.FAILED }
            assertEquals("old completed game", read(root, storedName))
            assertEquals(DownloadFailureCategory.VERIFICATION, downloads.downloads.value.first { it.fileName == name }.failure?.category)
            assertEquals(VerifyState.MISMATCH, downloads.verification.value[name])
            assertFalse(downloads.pendingAutoRetries.value.containsKey(name))
        }
    }

    private class Server(private val body: String, private val holdFirstBody: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val releaseFirst = CountDownLatch(1)
        val ranges = CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${listener.localPort}/game.gba"
        private val worker = Thread {
            try {
                var request = 0
                while (!listener.isClosed) listener.accept().use { socket ->
                    active.set(socket); request++
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(reader.readLine()?.startsWith("GET ") == true)
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                    }
                    if (range != null) ranges += range
                    val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val bytes = body.substring(start.coerceIn(0, body.length)).toByteArray()
                    val output = socket.getOutputStream()
                    val headers = buildString {
                        append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                        append("Content-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\nETag: \"fixture\"\r\nConnection: close\r\n")
                        if (start > 0) append("Content-Range: bytes $start-${body.length - 1}/${body.length}\r\n")
                        append("\r\n")
                    }
                    try {
                        output.write(headers.toByteArray(Charsets.US_ASCII))
                        if (holdFirstBody && request == 1) {
                            output.write(bytes, 0, 4); output.flush()
                            check(releaseFirst.await(20, TimeUnit.SECONDS))
                            // Closing the held body releases the paused worker's blocking read.
                        } else { output.write(bytes); output.flush() }
                    } catch (_: SocketException) { /* Cancellation can close the peer first. */ }
                    active.set(null)
                }
            } catch (e: Exception) { if (!listener.isClosed) failure.set(e) }
        }.apply { name = "safe-download-http"; isDaemon = true; start() }
        override fun close() {
            releaseFirst.countDown(); listener.close(); active.getAndSet(null)?.close(); worker.join(3_000)
            assertFalse("HTTP fixture did not stop", worker.isAlive)
            assertNull("HTTP fixture failed: ${failure.get()}", failure.get())
        }
    }
}
