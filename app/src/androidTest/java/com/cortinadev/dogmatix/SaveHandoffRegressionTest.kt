package com.cortinadev.dogmatix

import android.content.ContextWrapper
import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.util.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test


/** Actual HTTP multipart and SAF reads/writes, with isolated records/backups. */
class SaveHandoffRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, Handoff28TestEntryPoint::class.java)
    private val key = JournalKey("handoff-parent", "gba", "Game.gba")

    private suspend fun fixture(block: suspend (SaveSyncService, DocumentFile, Server, AppSettings, File) -> Unit) {
        val settings = graph.handoffSettings()
        val previous = settings.activeProfile.first()
        val previousFolders = settings.saveSyncEmulatorFolders.first()
        val dir = File(context.cacheDir, "handoff-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val testContext = InstrumentationRegistry.getInstrumentation().context
        testContext.grantUriPermission(context.packageName, tree, flags)
        val folder = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "handoff-${UUID.randomUUID()}"))
        val folderTree = DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getDocumentId(folder.uri))
        testContext.grantUriPermission(context.packageName, folderTree, flags)
        Server().use { server ->
            val fakeSettings = object : SettingsRepository by graph.handoffRepository() {
                override val rommUrl = MutableStateFlow(server.url)
                override val rommToken = MutableStateFlow("fixture-token")
                override val rommPlatformMap = MutableStateFlow(mapOf("gba" to 5))
                override val saveSyncSavesDir = MutableStateFlow(folderTree.toString())
                override val saveSyncStatesDir = MutableStateFlow(folderTree.toString())
            }
            val service = SaveSyncService(isolated, fakeSettings, RommClient(fakeSettings), settings,
                OperationHistoryService(isolated), graph.handoffLog(), graph.handoffAccess())
            try {
                settings.setSaveSyncEmulatorFolders(emptyList())
                settings.setActiveProfile(key.profileId)
                block(service, folder, server, settings, dir)
            } finally {
                settings.setSaveSyncEmulatorFolders(previousFolders)
                settings.setActiveProfile(previous)
                context.contentResolver.call(tree, "fixture:clear_faults", null, null)
                folder.delete()
                dir.deleteRecursively()
                testContext.revokeUriPermission(folderTree, flags)
                testContext.revokeUriPermission(tree, flags)
            }
        }
    }

    @Test fun oversizedSaveIsRejectedWithoutUploadOrChangingLocalBytes() = runBlocking { fixture { service, folder, server, _, _ ->
        val file = requireNotNull(folder.createFile("application/octet-stream", "Game.srm"))
        context.contentResolver.openOutputStream(file.uri, "wt")!!.use { out ->
            val chunk = ByteArray(1024 * 1024) { 42 }
            repeat(17) { out.write(chunk) }
        }
        assertTrue(runCatching { service.previewHandoff(key, 10) }.exceptionOrNull() is SaveHandoff.SaveTooLargeException)
        assertEquals(0, server.posts.get())
        assertEquals(17L * 1024 * 1024, file.length())
    } }
    @Test fun uploadVerifiesActualServerBytesAndBackupWhileLeavingEmulatorStatesUntouched() = runBlocking { fixture { service, folder, server, _, dir ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "precious-save".toByteArray())
        StorageHelper.writeBytesSafely(context, folder, "", "Game.state1", "emulator-state".toByteArray())
        val preview = service.previewHandoff(key, 10)
        assertEquals(SaveHandoffDirection.UPLOAD, preview.files.single().direction)
        assertEquals(1, preview.omittedStates)
        assertFalse(preview.ready)
        val completed = service.transferHandoff(preview)
        assertTrue(completed.ready)
        assertEquals("precious-save", server.save?.toString(Charsets.UTF_8))
        assertEquals(1, server.posts.get())
        assertEquals("emulator-state", StorageHelper.readText(context, folder.findFile("Game.state1")!!))
        val copies = service.localSafetyCopies(key.profileId)
        assertTrue(copies.isNotEmpty())
        copies.forEach { assertEquals("precious-save", VerifiedSafetyCopies.read(File(dir, "save-backups"), it.relative, 1024).toString(Charsets.UTF_8)) }
    } }
    @Test fun staleDeviceBytesBlockTransferBeforeAnyUploadOrDeletion() = runBlocking { fixture { service, folder, server, _, _ ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "before".toByteArray())
        val preview = service.previewHandoff(key, 10)
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "after".toByteArray())
        assertTrue(runCatching { service.transferHandoff(preview) }.isFailure)
        assertEquals(0, server.posts.get())
        assertEquals("after", StorageHelper.readText(context, folder.findFile("Game.srm")!!))
    } }
    @Test fun unknownDifferentCopiesAreBlockedInsteadOfChoosingTheNewerClock() = runBlocking { fixture { service, folder, server, _, _ ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "device-save".toByteArray())
        server.save = "server-save".toByteArray()
        val preview = service.previewHandoff(key, 10)
        assertEquals(SaveHandoffDirection.CONFLICT, preview.files.single().direction)
        assertFalse(preview.canTransfer)
        assertTrue(runCatching { service.transferHandoff(preview) }.isFailure)
        assertEquals(0, server.posts.get())
        assertEquals("device-save", StorageHelper.readText(context, folder.findFile("Game.srm")!!))
    } }
    @Test fun profileSwitchBeforeTransferBlocksTheOldApproval() = runBlocking { fixture { service, folder, server, settings, _ ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "parent-save".toByteArray())
        val preview = service.previewHandoff(key, 10)
        settings.setActiveProfile("handoff-child")
        assertTrue(runCatching { service.transferHandoff(preview) }.isFailure)
        assertEquals(0, server.posts.get())
    } }
    @Test fun profileCannotChangeInsideTheFinalRemoteMutation() = runBlocking { fixture { service, folder, server, settings, _ ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "parent-save".toByteArray())
        val preview = service.previewHandoff(key, 10)
        server.holdPost = true
        coroutineScope {
            val transfer = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { service.transferHandoff(preview) } }
            assertTrue(server.postStarted.await(15, TimeUnit.SECONDS))
            val switch = async { settings.setActiveProfile("handoff-child") }
            try {
                delay(150)
                assertFalse(switch.isCompleted)
                assertEquals("handoff-parent", settings.activeProfile.first())
            } finally { server.postRelease.countDown() }
            transfer.await()
            switch.await()
            assertEquals("handoff-child", settings.activeProfile.first())
            assertEquals("parent-save", server.save?.toString(Charsets.UTF_8))
        }
    } }
    @Test fun localRecoveryPreviewRejectsChangedTargetAndCorruptCopyWithoutCloudAccess() = runBlocking { fixture { service, folder, server, _, dir ->
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "saved-progress".toByteArray())
        service.transferHandoff(service.previewHandoff(key, 10))
        val copy = service.localSafetyCopies(key.profileId).first()
        val restore = service.previewLocalSafetyRestore(key.profileId, copy)
        StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "newer-progress".toByteArray())
        assertTrue(service.restoreLocalSafetyCopy(restore) is CloudSaveResult.Failed)
        assertEquals("newer-progress", StorageHelper.readText(context, folder.findFile("Game.srm")!!))
        File(dir, "save-backups/${copy.relative}").writeText("corruption")
        assertTrue(runCatching { service.previewLocalSafetyRestore(key.profileId, copy) }.isFailure)
        assertEquals(1, server.posts.get())
    } }

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        @Volatile var save: ByteArray? = null
        @Volatile var holdPost = false
        val posts = AtomicInteger()
        val postStarted = CountDownLatch(1)
        val postRelease = CountDownLatch(1)
        private val worker = Thread {
            while (!listener.isClosed) try { listener.accept().use(::respond) } catch (_: Exception) { if (listener.isClosed) break }
        }.apply { isDaemon = true; start() }
        private fun entry() = """{"id":1,"rom_id":10,"file_name":"Game.srm","emulator":"","updated_at":"2026-10-10T10:00:00Z","file_size_bytes":${save?.size ?: 0},"download_path":"/api/saves/1/content"}"""
        private fun respond(socket: Socket) {
            socket.soTimeout = 20_000
            val input = socket.getInputStream()
            fun line(): String {
                val buffer = java.io.ByteArrayOutputStream()
                while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) buffer.write(byte) }
                return buffer.toString("UTF-8")
            }
            val request = line().split(' ')
            val method = request.getOrElse(0) { "" }; val path = request.getOrElse(1) { "" }
            val headers = mutableMapOf<String, String>()
            while (true) { val row = line(); if (row.isEmpty()) break; headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..1024 * 1024)
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) { val count = input.read(body, offset, body.size - offset); if (count < 0) error("Missing body"); offset += count }
            val response: ByteArray = when {
                method == "POST" && path.startsWith("/api/saves?") -> {
                    posts.incrementAndGet(); postStarted.countDown()
                    if (holdPost) check(postRelease.await(15, TimeUnit.SECONDS))
                    val raw = body.toString(Charsets.ISO_8859_1)
                    val boundary = headers["content-type"]!!.substringAfter("boundary=").trim('"')
                    val start = raw.indexOf("\r\n\r\n") + 4
                    val end = raw.lastIndexOf("\r\n--$boundary")
                    require(start >= 4 && end >= start)
                    save = raw.substring(start, end).toByteArray(Charsets.ISO_8859_1)
                    entry().toByteArray()
                }
                path == "/api/roms/10" -> """{"id":10,"fs_name":"Game.gba","platform_id":5}""".toByteArray()
                path.startsWith("/api/roms?") -> """[{"id":10,"fs_name":"Game.gba","platform_slug":"gba","platform_fs_slug":"gba"}]""".toByteArray()
                path == "/api/saves/1/content" -> save ?: byteArrayOf()
                path == "/api/saves" -> (if (save == null) "[]" else "[${entry()}]").toByteArray()
                else -> "[]".toByteArray()
            }
            socket.getOutputStream().use { out ->
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(response); out.flush()
            }
        }
        override fun close() { postRelease.countDown(); listener.close(); worker.join(2000) }
    }
}
