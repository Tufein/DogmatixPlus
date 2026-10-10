package com.cortinadev.dogmatix

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.StorageAvailabilityService
import com.cortinadev.dogmatix.data.service.StorageDownloadHolds
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StorageRecoveryRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, ExplorerRecoveryEntryPoint::class.java)
    private val sourceGraph get() = EntryPointAccessors.fromApplication(context.applicationContext, ManualSourceEntryPoint::class.java)

    private suspend fun fixture(block: suspend (Uri, DocumentFile, DocumentFile) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        test.grantUriPermission(context.packageName, tree, flags)
        val base = StorageHelper.createDirectory(context, tree.toString(), "storage-recovery-${UUID.randomUUID()}")!!
        val first = base.createDirectory("first")!!
        val second = base.createDirectory("second")!!
        val settings = graph.explorer27SettingsRepository()
        val previous = settings.downloadDirectory.first()
        val appSettings = graph.recovery28AppSettings()
        val profile = appSettings.activeProfile.first()
        try {
            appSettings.setActiveProfile("")
            settings.updateDownloadDirectory(first.uri.toString())
            block(tree, first, second)
        } finally {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            settings.updateDownloadDirectory(previous)
            appSettings.setActiveProfile(profile)
            base.delete()
            test.revokeUriPermission(tree, flags)
        }
    }

    @Test fun exactChildAvailabilityAndWritableProbeNeverFallBackOrLeaveProbeFiles() = runBlocking {
        fixture { tree, first, second ->
            val caseUpper = DocumentsContract.buildDocumentUriUsingTree(tree, "root/Games").toString()
            val caseLower = DocumentsContract.buildDocumentUriUsingTree(tree, "root/games").toString()
            assertNotEquals(StorageAvailabilityService.key(caseUpper), StorageAvailabilityService.key(caseLower))
            val service = graph.recovery28Availability()
            assertEquals(StorageAccessStatus.AVAILABLE, StorageAvailabilityService.probe(context, first.uri.toString()))
            context.contentResolver.call(tree, "fixture:fail_query", "first", null)
            assertEquals(StorageAccessStatus.UNAVAILABLE, StorageAvailabilityService.probe(context, first.uri.toString()))
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            context.contentResolver.call(tree, "fixture:fail_loading", "first", null)
            assertEquals(StorageAccessStatus.UNAVAILABLE, StorageAvailabilityService.probe(context, first.uri.toString()))
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            context.contentResolver.call(tree, "fixture:fail_access", "first", null)
            assertEquals(StorageAccessStatus.ACCESS_LOST, StorageAvailabilityService.probe(context, first.uri.toString()))
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            assertTrue(service.verifyWritable(first.uri.toString()))
            assertTrue(first.listFiles().isEmpty())
            context.contentResolver.call(tree, "fixture:fail_write", ".dogmatix-probe-*", null)
            assertFalse(service.verifyWritable(first.uri.toString()))
            assertTrue(first.listFiles().isEmpty())
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            assertTrue(service.verifyWritable(first.uri.toString()))
            assertTrue(first.listFiles().isEmpty())
            val missingUri = first.uri.toString()
            assertTrue(first.delete())
            assertEquals(StorageAccessStatus.MISSING, StorageAvailabilityService.probe(context, missingUri))
            assertFalse(service.verifyWritable(missingUri))
            assertTrue(second.listFiles().isEmpty())
        }
    }

    @Test fun explicitStorageResumePreservesPartialBytesAndOnlyResumesTheExactRoot() = runBlocking {
        fixture { tree, first, second ->
            val source = sourceGraph
            val downloads = source.downloads()
            val db = source.database()
            val settings = graph.explorer27SettingsRepository()
            val id = "storage-resume-${UUID.randomUUID()}"
            val a = DownloadableFileEntity(name = "First storage", fileName = "$id-a.gba", consoleId = "$id-a",
                downloadUrl = "https://storage-recovery.test/a.gba", sourceUrl = SOURCE, fileSize = 1024L)
            val b = a.copy(name = "Second storage", fileName = "$id-b.gba", consoleId = "$id-b", downloadUrl = "https://storage-recovery.test/b.gba")
            val heldBefore = downloads.gate.held.value
            val pickBefore = source.sourcePick().pickBest.first()
            val overridesBefore = settings.consoleDownloadDirectories.first()
            val identities = listOf(graph.recovery28Packages().identity(a), graph.recovery28Packages().identity(b))
            val otherSourceIdentity = graph.recovery28Packages().identity(b.copy(downloadUrl = "https://changed-source.test/b.gba"))
            try {
                downloads.gate.setHeld(true)
                source.sourcePick().setPickBest(false)
                db.consoleDao().insertConsole(ConsoleEntity(a.consoleId, a.name, id, """[{"url":"$SOURCE","enabled":true}]"""))
                db.consoleDao().insertConsole(ConsoleEntity(b.consoleId, b.name, id, """[{"url":"$SOURCE","enabled":true}]"""))
                db.downloadableFileDao().insertFiles(listOf(a, b))
                settings.updateConsoleDownloadDirectory(b.consoleId, second.uri.toString())
                val tomorrow = DownloadCondition(ConditionKind.AT_TIME, atMillis = System.currentTimeMillis() + 86_400_000L)
                withContext(Dispatchers.Default) { downloads.startDownloads(listOf(a, b), tomorrow) }
                eventually { downloads.downloads.value.count { it.fileName in setOf(a.fileName, b.fileName) } == 2 }
                withContext(Dispatchers.Default) { downloads.setCondition(listOf(a.fileName, b.fileName), null) }
                eventually { setOf(a.fileName, b.fileName).all { it in downloads.waitingFiles.value } }
                val manager = graph.recovery28FileManager()
                val partial = manager.createDocumentFile(a, first.uri.toString(), manager.getSubPath(a))!!
                context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { it.write("valuable partial bytes".toByteArray()) }

                downloads.holdStorageUnavailable(first.uri.toString())
                eventually { downloads.downloads.value.first { it.fileName == a.fileName }.status == DownloadStatus.PAUSED }
                assertEquals(DownloadStatus.DOWNLOADING, downloads.downloads.value.first { it.fileName == b.fileName }.status)
                assertEquals(listOf(a.fileName), downloads.resumableStorageDownloads(first.uri.toString()))
                assertTrue(downloads.resumableStorageDownloads(second.uri.toString()).isEmpty())
                // A user pause and a stale marker for the same name from another source cannot be resumed here.
                withContext(Dispatchers.Default) { downloads.pauseDownload(b.fileName, byUser = true) }
                eventually { downloads.downloads.value.first { it.fileName == b.fileName }.status == DownloadStatus.PAUSED }
                graph.recovery28DownloadHolds().hold(listOf(otherSourceIdentity), second.uri.toString())
                assertTrue(downloads.resumableStorageDownloads(second.uri.toString()).isEmpty())
                assertEquals(StorageAvailabilityService.key(first.uri.toString()), StorageDownloadHolds(context).snapshot()[identities.first()])
                graph.recovery28Availability().recheck()
                assertEquals(DownloadStatus.PAUSED, downloads.downloads.value.first { it.fileName == a.fileName }.status)
                assertEquals("valuable partial bytes", StorageHelper.readText(context, partial))
                assertEquals(0, downloads.resumeStorageDownloads(second.uri.toString()))
                context.contentResolver.call(tree, "fixture:fail_write", ".dogmatix-probe-*", null)
                assertEquals(0, downloads.resumeStorageDownloads(first.uri.toString()))
                assertEquals(DownloadStatus.PAUSED, downloads.downloads.value.first { it.fileName == a.fileName }.status)
                assertEquals("valuable partial bytes", StorageHelper.readText(context, partial))
                context.contentResolver.call(tree, "fixture:clear_faults", null, null)
                val holdsBefore = StorageDownloadHolds(context).snapshot()
                assertTrue(runCatching { downloads.resumeStorageDownloads(first.uri.toString(), "stale-other-profile") }.isFailure)
                assertEquals(holdsBefore, StorageDownloadHolds(context).snapshot())
                assertEquals(DownloadStatus.PAUSED, downloads.downloads.value.first { it.fileName == a.fileName }.status)
                assertEquals(1, downloads.resumeStorageDownloads(first.uri.toString()))
                eventually { a.fileName in downloads.waitingFiles.value }
                assertEquals("valuable partial bytes", StorageHelper.readText(context, partial))
                assertFalse(StorageDownloadHolds(context).snapshot().containsKey(identities.first()))
                assertFalse(downloads.isTransferring(a.fileName))
            } finally {
                withContext(Dispatchers.Default) { downloads.deleteDownload(a.fileName); downloads.deleteDownload(b.fileName) }
                eventually { downloads.entityFor(a.fileName) == null && downloads.entityFor(b.fileName) == null }
                graph.recovery28DownloadHolds().release(identities + otherSourceIdentity)
                source.partials().remove(a.fileName); source.partials().remove(b.fileName)
                settings.updateConsoleDownloadDirectory(b.consoleId, overridesBefore[b.consoleId].orEmpty())
                db.consoleDao().deleteConsoleById(a.consoleId); db.consoleDao().deleteConsoleById(b.consoleId)
                source.sourcePick().setPickBest(pickBefore)
                downloads.gate.setHeld(heldBefore)
            }
        }
    }

    @Test fun recoveryHubFindsNestedReceiptsAndRevalidatesTargetsAndProfilesAtCommit() = runBlocking {
        fixture { tree, first, _ ->
            val folder = first.createDirectory("nested")!!
            StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "previous precious save".toByteArray())
            context.contentResolver.call(tree, "fixture:fail_commit", "Game.srm", null)
            assertTrue(runCatching { StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "replacement save".toByteArray()) }.isFailure)
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            assertNull(folder.findFile("Game.srm"))
            val hub = graph.recovery28Hub()
            val entry = hub.snapshot().replacements.single { it.folderUri == folder.uri.toString() }
            assertTrue(entry.folderLabel.endsWith("/nested"))
            val preview = hub.previewReplacement(entry)
            assertNull(folder.findFile("Game.srm"))

            val current = folder.createFile("application/octet-stream", "Game.srm")!!
            context.contentResolver.openOutputStream(current.uri, "wt")!!.use { it.write("newer precious save".toByteArray()) }
            assertTrue(runCatching { hub.restoreReplacement(preview) }.isFailure)
            assertEquals("newer precious save", StorageHelper.readText(context, current))
            assertEquals("previous precious save", StorageHelper.readText(context, folder.findFile(entry.recovery.backupName)!!))
            assertEquals(1, StorageHelper.pendingRecoveries(context, folder).size)
            assertTrue(current.delete())

            val app = graph.recovery28AppSettings()
            val profiles = app.profiles.first()
            try {
                app.setProfiles(Profiles.toJson(listOf(Profile("recovery-child", "Child", hiddenConsoles = setOf("gba")))))
                app.setActiveProfile("recovery-child")
                assertTrue(hub.snapshot().replacements.isEmpty())
                assertTrue(runCatching { hub.restoreReplacement(preview) }.isFailure)
                assertNull(folder.findFile("Game.srm"))
            } finally {
                app.setActiveProfile("")
                app.setProfiles(profiles)
            }
            hub.restoreReplacement(hub.previewReplacement(entry))
            assertEquals("previous precious save", StorageHelper.readText(context, folder.findFile("Game.srm")!!))
            assertTrue(StorageHelper.pendingRecoveries(context, folder).isEmpty())
        }
    }

    @Test fun reconstructedServiceKeepsExactStorageHoldsPausedAndUserStopRevokesThemPermanently() = runBlocking {
        fixture { _, first, second ->
            val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
            val prefix = "restart-storage-${UUID.randomUUID()}"
            val entities = (0..4).map { index -> DownloadableFileEntity(name = "Storage restart $index", fileName = "$prefix-$index.gba",
                consoleId = prefix, downloadUrl = "https://storage-recovery.test/$index.gba", fileSize = 1024L) }
            val identities = entities.map { graph.recovery28Packages().identity(it) }
            val wrongSource = graph.recovery28Packages().identity(entities[4].copy(downloadUrl = "https://changed.test/different.gba"))
            val history = database.downloadHistoryDao()
            val reconstructed = ArrayList<com.cortinadev.dogmatix.data.service.DownloadService>()
            try {
                val rows = entities.mapIndexed { index, file -> DownloadHistoryEntity.from(file, DownloadItemModel(file.name, file.fileName,
                    0f, 0f, file.fileSize, status = if (index == 1 || index == 2) DownloadStatus.STOPPED else DownloadStatus.PAUSED,
                    startedAt = System.currentTimeMillis())) }
                history.upsertAll(rows)
                graph.recovery28DownloadHolds().hold(identities.take(2), first.uri.toString())
                graph.recovery28DownloadHolds().hold(listOf(identities[3]), second.uri.toString())
                graph.recovery28DownloadHolds().hold(listOf(wrongSource), first.uri.toString())
                val partial = graph.recovery28FileManager().createDocumentFile(entities[0], first.uri.toString(), "")!!
                context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { it.write("restart partial retained".toByteArray()) }
                val service = graph.recovery28ServiceFactory().create(history).also(reconstructed::add)
                eventually { service.downloads.value.size == entities.size }
                assertEquals(listOf(DownloadStatus.PAUSED, DownloadStatus.PAUSED, DownloadStatus.STOPPED, DownloadStatus.STOPPED, DownloadStatus.STOPPED),
                    entities.map { file -> service.downloads.value.first { it.fileName == file.fileName }.status })
                assertEquals(entities.take(2).map { it.fileName }.sorted(), service.resumableStorageDownloads(first.uri.toString()))
                assertEquals("restart partial retained", StorageHelper.readText(context, partial))
                assertTrue(entities.none { service.isTransferring(it.fileName) })
                withContext(Dispatchers.Default) { service.cancelDownload(entities[0].fileName) }
                assertEquals(DownloadStatus.STOPPED, service.downloads.value.first { it.fileName == entities[0].fileName }.status)
                assertFalse(StorageDownloadHolds(context).snapshot().containsKey(identities[0]))
                assertEquals(listOf(entities[1].fileName), service.resumableStorageDownloads(first.uri.toString()))
                eventually { history.getAll().first { it.fileName == entities[0].fileName }.status == DownloadStatus.STOPPED.name }
                withContext(Dispatchers.Default) { service.deleteDownload(entities[1].fileName) }
                eventually { history.getAll().none { it.fileName == entities[1].fileName } }
                assertFalse(StorageDownloadHolds(context).snapshot().containsKey(identities[1]))
                val restarted = graph.recovery28ServiceFactory().create(history).also(reconstructed::add)
                eventually { restarted.downloads.value.size == entities.size - 1 }
                assertEquals(DownloadStatus.STOPPED, restarted.downloads.value.first { it.fileName == entities[0].fileName }.status)
                assertTrue(restarted.resumableStorageDownloads(first.uri.toString()).isEmpty())
                assertEquals("restart partial retained", StorageHelper.readText(context, partial))
            } finally {
                reconstructed.forEach { service ->
                    // Only cleanup reaches the private scopes; assertions use the public service API.
                    listOf(service to "serviceScope").forEach { (owner, name) ->
                        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        (field.get(owner) as CoroutineScope).cancel()
                    }
                    val trackerField = service.javaClass.getDeclaredField("downloadProgressTracker").apply { isAccessible = true }
                    val tracker = trackerField.get(service)
                    val scope = tracker.javaClass.getDeclaredField("persistScope").apply { isAccessible = true }
                    (scope.get(tracker) as CoroutineScope).cancel()
                }
                graph.recovery28DownloadHolds().release(identities + wrongSource)
                database.close()
            }
        }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(25)
    }
    private companion object { const val SOURCE = "https://storage-recovery.test/" }
}
