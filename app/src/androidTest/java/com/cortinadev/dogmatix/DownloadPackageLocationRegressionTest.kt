package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.util.GamePackages
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Public queue APIs must keep a recorded package tied to its original folder after settings change. */
class DownloadPackageLocationRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, ExplorerRecoveryEntryPoint::class.java)
    private val sourceGraph get() = EntryPointAccessors.fromApplication(context.applicationContext, ManualSourceEntryPoint::class.java)

    @Test fun recordedPackageKeepsItsOriginalSubfolderAndNeverFallsBackIntoAnotherRootOrCorruptReceipt() = runBlocking {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        val settings = graph.explorer27SettingsRepository()
        val oldRoot = settings.downloadDirectory.first()
        val oldSeparate = settings.separateByConsole.first()
        val appSettings = graph.recovery28AppSettings()
        val oldProfile = appSettings.activeProfile.first()
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val id = "package-location-${UUID.randomUUID()}"
        val file = DownloadableFileEntity(name = "Recorded package", fileName = "$id.zip", consoleId = id,
            downloadUrl = "https://package-location.test/$id.zip", fileSize = 1024L)
        val packages = graph.recovery28Packages()
        val consoleDao = sourceGraph.database().consoleDao()
        val sourceConsole = ConsoleEntity(id, "Original console", id, "[]", shortName = "Original", folderAliases = "original-console")
        test.grantUriPermission(context.packageName, tree, flags)
        val base = StorageHelper.createDirectory(context, tree.toString(), id)!!
        val rootA = base.createDirectory("rootA")!!
        val rootB = base.createDirectory("rootB")!!
        val subPath = "original-console"
        val romPath = "Disc 1/Game.cue"
        var service: DownloadService? = null
        val receiptFile = File(context.filesDir, "game-packages/${GamePackages.receiptKey(packages.identity(file), rootA.uri.toString())}.json")
        try {
            appSettings.setActiveProfile("")
            settings.updateDownloadDirectory(rootA.uri.toString())
            settings.setSeparateByConsole(true)
            consoleDao.insertConsole(sourceConsole)
            val original = StorageHelper.writeBytesSafely(context, rootA, "$subPath/Disc 1", "Game.cue", "original package".toByteArray())
            StorageHelper.writeBytesSafely(context, rootA, "$subPath/Disc 1", "Track.bin", "original track".toByteArray())
            packages.record(file, rootA.uri.toString(), subPath, listOf(romPath, "Disc 1/Track.bin"))
            database.downloadHistoryDao().upsertAll(listOf(DownloadHistoryEntity.from(file,
                DownloadItemModel(file.name, file.fileName, 0f, 1f, file.fileSize, file.fileSize, DownloadStatus.COMPLETED, System.currentTimeMillis()))))
            service = graph.recovery28ServiceFactory().create(database.downloadHistoryDao())
            val downloads = requireNotNull(service)
            eventually { downloads.entityFor(file.fileName) != null && downloads.downloads.value.any { it.fileName == file.fileName } }
            assertEquals(subPath, downloads.downloadedPackageLocation(file)!!.subPath)
            assertEquals(original.uri, downloads.openIntentFor(file.fileName)!!.data)

            // New console naming and folder policy do not redirect the recorded archive's parts.
            settings.setSeparateByConsole(false)
            consoleDao.updateConsole(sourceConsole.copy(name = "Renamed console", shortName = "Renamed", folderAliases = "renamed-console"))
            val incorrectFlat = StorageHelper.writeBytesSafely(context, rootA, "Disc 1", "Game.cue", "different flat game".toByteArray())
            assertEquals(subPath, downloads.downloadedPackageLocation(file)!!.subPath)
            assertEquals(original.uri, downloads.openIntentFor(file.fileName)!!.data)
            assertNotEquals(incorrectFlat.uri, downloads.openIntentFor(file.fileName)!!.data)

            // Same relative names in a newly selected root must not inherit ownership from rootA.
            StorageHelper.writeBytesSafely(context, rootB, "$subPath/Disc 1", "Game.cue", "other game's cue".toByteArray())
            StorageHelper.writeBytesSafely(context, rootB, "$subPath/Disc 1", "Track.bin", "other game's track".toByteArray())
            StorageHelper.writeBytesSafely(context, rootB, "", file.fileName, "unrelated archive".toByteArray())
            settings.updateDownloadDirectory(rootB.uri.toString())
            assertNull(downloads.downloadedPackageLocation(file))
            assertNull(downloads.openIntentFor(file.fileName))

            // A corrupt receipt is evidence of recorded ownership, never permission for basename fallback.
            settings.updateDownloadDirectory(rootA.uri.toString())
            StorageHelper.writeBytesSafely(context, rootA, "", file.fileName, "unrelated root archive".toByteArray())
            assertTrue(receiptFile.isFile)
            receiptFile.writeText("{\"schema\":999,\"parts\":[]}")
            assertNull(downloads.downloadedPackageLocation(file))
            assertNull(downloads.openIntentFor(file.fileName))
            assertEquals("original package", StorageHelper.readText(context, original))
        } finally {
            service?.let { downloads ->
                val scope = downloads.javaClass.getDeclaredField("serviceScope").apply { isAccessible = true }
                (scope.get(downloads) as CoroutineScope).cancel()
                val trackerField = downloads.javaClass.getDeclaredField("downloadProgressTracker").apply { isAccessible = true }
                val tracker = trackerField.get(downloads)
                val persistScope = tracker.javaClass.getDeclaredField("persistScope").apply { isAccessible = true }
                (persistScope.get(tracker) as CoroutineScope).cancel()
            }
            receiptFile.delete(); File(receiptFile.path + ".bak").delete()
            val tracked = File(context.filesDir, "game-packages/${packages.identity(file)}.tracked")
            tracked.delete(); File(tracked.path + ".bak").delete()
            consoleDao.deleteConsoleById(id)
            settings.updateDownloadDirectory(oldRoot)
            settings.setSeparateByConsole(oldSeparate)
            appSettings.setActiveProfile(oldProfile)
            base.delete()
            test.revokeUriPermission(tree, flags)
            database.close()
        }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(25) }
}
