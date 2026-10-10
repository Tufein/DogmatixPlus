package com.cortinadev.dogmatix

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveBackupSafetyRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = EntryPointAccessors.fromApplication(context.applicationContext, SaveSafetyEntryPoint::class.java)

    private suspend fun fixture(block: suspend (Uri, String) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val testContext = InstrumentationRegistry.getInstrumentation().context
        testContext.grantUriPermission(context.packageName, tree, flags)
        val dir = "save-safety-${UUID.randomUUID()}"
        try {
            assertNotNull(StorageHelper.createDirectory(context, tree.toString(), dir))
            block(tree, dir)
        } finally {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            StorageHelper.getDocumentFile(context, tree.toString())?.findFile(dir)?.delete()
            testContext.revokeUriPermission(tree, flags)
        }
    }

    @Test fun realSaveBackupReadFailureKeepsTheOriginalAndReportsAFailure() = runBlocking {
        fixture { tree, dir ->
            val destination = StorageHelper.createDirectory(context, tree.toString(), dir)!!
            val name = "backup-safety-${UUID.randomUUID()}.srm"
            val document = StorageHelper.writeBytesSafely(context, destination, "", name, "precious save progress".toByteArray())
            val local = LocalSaveFile(SaveKind.SAVE, name, document.length(), document.lastModified())
            val type = Class.forName("com.cortinadev.dogmatix.data.service.SaveSyncService\$SafSaveStore")
            val real = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(services.saveSafetySyncService()) as SaveStore
            type.getDeclaredField("documents").apply { isAccessible = true }.set(real, mapOf(SaveSyncEngine.key(local) to document.uri))
            val store = object : SaveStore by real {
                override suspend fun list() = SaveStore.Listing(listOf(local), mapOf(SaveKind.SAVE to emptySet()))
            }
            val server = object : SaveServer {
                override suspend fun list(kind: SaveKind) = emptyList<RemoteSaveFile>()
                override suspend fun download(save: RemoteSaveFile): ByteArray = error("No download expected")
                override suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray): RemoteSaveFile? = error("No upload expected")
                override suspend fun searchRoms(term: String) = emptyList<SaveSyncPlanner.RomCandidate>()
                override suspend fun delete(save: RemoteSaveFile) = error("No remote deletion expected")
            }
            val remote = RemoteSaveFile(SaveKind.SAVE, 1, 1, name, null, "2026-10-09T00:00:00Z", local.size, "unused")
            val records = mutableMapOf(SaveSyncEngine.key(local) to SaveSyncEngine.record(local, remote))
            val before = records.toMap()
            context.contentResolver.call(tree, "fixture:fail_read", name, null)
            val result = try {
                SaveSyncEngine(server, store, syncDeletions = { true }).sync(records).first
            } finally { context.contentResolver.call(tree, "fixture:fail_read", null, null) }
            assertEquals(0, result.deletedOnDevice)
            assertEquals(1, result.failed)
            assertEquals(before, records)
            assertEquals("precious save progress", StorageHelper.readText(context, destination.findFile(name)!!))
            assertTrue(File(context.filesDir, "save-backups").walkTopDown().none { it.name == name && it.isFile })
        }
    }

    @Test fun damagedSafetyCopyCannotReplaceTheCurrentSave() = runBlocking {
        fixture { tree, dir ->
            val service = services.saveSafetySyncService()
            val settings = services.saveSafetySettingsRepository()
            val app = services.saveSafetyAppSettings()
            val previousSaves = settings.saveSyncSavesDir.first()
            val previousStates = settings.saveSyncStatesDir.first()
            val previousEmulators = app.saveSyncEmulatorFolders.first()
            val destination = StorageHelper.createDirectory(context, tree.toString(), dir)!!
            val name = "restore-safety-${UUID.randomUUID()}.srm"
            StorageHelper.writeBytesSafely(context, destination, "", name, "current precious save".toByteArray())
            val path = "$dir/$name"
            val target = VerifiedSafetyCopies.keep(service.safetyCopiesDir, SaveKind.SAVE, path, "older save".toByteArray())
            val copy = SafetyCopy(SaveKind.SAVE, path, target.relativeTo(service.safetyCopiesDir).invariantSeparatorsPath, target.lastModified(), target.length())
            try {
                settings.setSaveSyncSavesDir(tree.toString())
                settings.setSaveSyncStatesDir("")
                app.setSaveSyncEmulatorFolders(emptyList())
                target.writeText("wrong save")
                val result = service.restoreSafetyCopy(copy, emptyList())
                assertTrue(result is CloudSaveResult.Failed)
                assertEquals(context.getString(R.string.csave_error_integrity), (result as CloudSaveResult.Failed).message)
                assertEquals("current precious save", StorageHelper.readText(context, destination.findFile(name)!!))
            } finally {
                settings.setSaveSyncSavesDir(previousSaves)
                settings.setSaveSyncStatesDir(previousStates)
                app.setSaveSyncEmulatorFolders(previousEmulators)
                File(service.safetyCopiesDir, copy.relative.substringBefore('/')).deleteRecursively()
            }
        }
    }
}
