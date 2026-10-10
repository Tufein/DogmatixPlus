package com.cortinadev.dogmatix

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadFileManager
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.VerifiedCopy
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual SAF write/read/commit/rollback faults; assertions require the original bytes to survive. */
class StorageSafetyRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
    private val manager get() = EntryPointAccessors.fromApplication(context.applicationContext, StorageSafetyEntryPoint::class.java).fileManager()

    private fun fixture(test: (DocumentFile, String) -> Unit) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        testContext.grantUriPermission(context.packageName, tree, flags)
        val folder = "storage-safety-${UUID.randomUUID()}"
        val dir = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), folder))
        try { clear(); test(dir, folder) }
        finally { clear(); dir.delete(); testContext.revokeUriPermission(tree, flags) }
    }

    private fun clear() { context.contentResolver.call(tree, "fixture:clear_faults", null, null) }
    private fun fault(type: String, name: String?) { context.contentResolver.call(tree, "fixture:fail_$type", name, null) }
    private fun write(dir: DocumentFile, name: String, body: String): DocumentFile = StorageHelper.writeBytesSafely(context, dir, "", name, body.toByteArray())
    private fun read(dir: DocumentFile, name: String): String = StorageHelper.readText(context, requireNotNull(dir.findFile(name)))
    private fun row(name: String) = DownloadableFileEntity(consoleId = "gba", name = "Game", fileName = name,
        downloadUrl = "https://example.invalid/$name", fileSize = 8, fileExtension = "gba")
    private fun hash(body: String): String = VerifiedCopy.hash(body.byteInputStream())
    private fun fill(doc: DocumentFile, body: String) { context.contentResolver.openOutputStream(doc.uri, "wt")!!.use { it.write(body.toByteArray()) } }

    @Test fun failedNewDownloadCreationPreservesTheExistingCompletedGame() = fixture { dir, folder ->
        write(dir, "Game.gba", "original")
        fault("create", ".dogmatix-download-*")
        assertNull(manager.createDocumentFile(row("Game.gba"), tree.toString(), folder))
        clear()
        assertEquals("original", read(dir, "Game.gba"))
        assertEquals(listOf("Game.gba"), dir.listFiles().map { it.name })
    }

    @Test fun interruptedRedownloadResumesOnlyItsPartAndPublishesAfterVerification() = fixture { dir, folder ->
        val file = row("Game.gba")
        write(dir, "Game.gba", "original")
        assertNull(manager.findExistingFile(file, tree.toString(), folder))
        val part = requireNotNull(manager.createDocumentFile(file, tree.toString(), folder))
        fill(part, "new")
        assertEquals("original", read(dir, "Game.gba"))
        val continued = requireNotNull(manager.findExistingFile(file, tree.toString(), folder))
        assertEquals(part.uri, continued.uri)
        manager.getAppendOutputStream(continued)!!.use { it.write(" data".toByteArray()) }
        assertEquals("original", read(dir, "Game.gba"))
        val published = manager.commitDocumentFile(file, tree.toString(), folder, continued, hash("new data"))
        assertEquals("Game.gba", published.name)
        assertEquals("new data", read(dir, "Game.gba"))
        assertNull(manager.findExistingFile(file, tree.toString(), folder))
        assertTrue(StorageHelper.pendingRecoveries(context, dir).isEmpty())
    }

    @Test fun damagedDownloadReadbackCannotReplaceTheOriginal() = fixture { dir, folder ->
        val file = row("Game.gba")
        write(dir, "Game.gba", "original")
        val part = requireNotNull(manager.createDocumentFile(file, tree.toString(), folder))
        fill(part, "wrong bytes")
        assertThrows(Exception::class.java) { manager.commitDocumentFile(file, tree.toString(), folder, part, hash("correct bytes")) }
        assertEquals("original", read(dir, "Game.gba"))
        assertEquals("wrong bytes", StorageHelper.readText(context, part))
    }

    @Test fun failedCommitAndFailedRollbackKeepVerifiedOriginalAndDurableRecoveryReceipt() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        fault("commit", "Game.srm")
        assertThrows(Exception::class.java) { write(dir, "Game.srm", "new save") }
        clear()
        val recovery = StorageHelper.pendingRecoveries(context, dir).single()
        assertEquals("Game.srm", recovery.fileName)
        assertEquals("old save", read(dir, recovery.backupName))
        assertNull(dir.findFile("Game.srm"))
        val reloadedDir = requireNotNull(StorageHelper.getDocumentFile(context, dir.uri.toString()))
        assertEquals(listOf(recovery), StorageHelper.pendingRecoveries(context, reloadedDir))
        StorageHelper.restoreRecovery(context, reloadedDir, recovery)
        assertEquals("old save", read(dir, "Game.srm"))
        assertTrue(StorageHelper.pendingRecoveries(context, dir).isEmpty())
    }

    @Test fun stageWriteReadAndBackupWriteFailuresLeaveOriginalAtItsUsualPath() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        for ((type, name) in listOf("write" to ".dogmatix-write-*", "read" to ".dogmatix-write-*", "read" to "Game.srm", "write" to ".dogmatix-recovery-*")) {
            fault(type, name)
            assertThrows("$type $name should fail safely", Exception::class.java) { write(dir, "Game.srm", "new save") }
            clear()
            assertEquals("old save", read(dir, "Game.srm"))
        }
    }

    @Test fun originalDeleteFailureIsReportedWithoutDiscardingTheOriginal() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        fault("delete", "Game.srm")
        assertThrows(Exception::class.java) { write(dir, "Game.srm", "new save") }
        clear()
        assertEquals("old save", read(dir, "Game.srm"))
    }

    @Test fun cancellationDuringVerificationCannotRemoveTheCompletedOriginal() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        val incoming = write(dir, "incoming.part", "new save")
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            StorageHelper.publishStagedFile(context, dir, incoming, "Game.srm", hash("new save")) {
                // Simulate a pause after the durable recovery is ready, before publication.
                if (StorageHelper.pendingRecoveries(context, dir).isNotEmpty()) throw kotlinx.coroutines.CancellationException("Paused")
            }
        }
        assertEquals("old save", read(dir, "Game.srm"))
        assertEquals("new save", StorageHelper.readText(context, incoming))
        assertTrue(StorageHelper.pendingRecoveries(context, dir).isEmpty())
    }

    @Test fun providersWithoutRenameUseVerifiedCopyFallback() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        fault("rename", "*")
        write(dir, "Game.srm", "new save")
        clear()
        assertEquals("new save", read(dir, "Game.srm"))
        assertTrue(StorageHelper.pendingRecoveries(context, dir).isEmpty())
    }

    @Test fun recoveryCannotOverwriteADifferentNewerSaveAndSurvivesRefusedCleanup() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        fault("delete", ".dogmatix-recovery-*")
        write(dir, "Game.srm", "new save")
        clear()
        val recovery = StorageHelper.pendingRecoveries(context, dir).single()
        assertThrows(Exception::class.java) { StorageHelper.restoreRecovery(context, dir, recovery) }
        assertEquals("new save", read(dir, "Game.srm"))
        assertEquals("old save", read(dir, recovery.backupName))
    }

    @Test fun unreadablePublishedFileKeepsItsBytesAndTheVerifiedOriginalRecovery() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        // Refuse only the final read, after the original and its backup passed validation.
        fault("read_after_rename", "Game.srm")
        assertThrows(Exception::class.java) { write(dir, "Game.srm", "new save") }
        clear()
        assertEquals("new save", read(dir, "Game.srm"))
        val recovery = StorageHelper.pendingRecoveries(context, dir).single()
        assertEquals("old save", read(dir, recovery.backupName))
    }

    @Test fun externalChangeAfterPublicationIsPreservedAlongsideTheOriginalRecovery() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        fault("modified_after_rename", "Game.srm")
        assertThrows(Exception::class.java) { write(dir, "Game.srm", "new save") }
        clear()
        assertEquals("external newer bytes", read(dir, "Game.srm"))
        val recovery = StorageHelper.pendingRecoveries(context, dir).single()
        assertEquals("old save", read(dir, recovery.backupName))
    }

    @Test fun providerChangedBackupOrReceiptNamesCannotPutTheOriginalAtRisk() = fixture { dir, _ ->
        write(dir, "Game.srm", "old save")
        for (pattern in listOf(".dogmatix-recovery-*", "*.json")) {
            fault("display_name", pattern)
            assertThrows(Exception::class.java) { write(dir, "Game.srm", "new save") }
            clear()
            assertEquals("old save", read(dir, "Game.srm"))
            assertEquals(listOf("Game.srm"), dir.listFiles().map { it.name })
        }
    }

    @Test fun sourcePathsAndLiteralPlusNamesReachOnlyTheirBasename() = fixture { dir, folder ->
        for ((index, reference) in listOf("./First.gba", "nested/Second.gba", "https://example.invalid/gba/Third.gba?token=abc", "nested/C++%20Game.gba").withIndex()) {
            val file = row(reference)
            val part = requireNotNull(manager.createDocumentFile(file, tree.toString(), folder))
            fill(part, "body$index")
            val final = manager.commitDocumentFile(file, tree.toString(), folder, part, hash("body$index"))
            assertEquals(reference.substringBefore('?').substringAfterLast('/').replace("%20", " "), final.name)
            assertFalse(final.name!!.contains('/'))
        }
        assertEquals("body3", read(dir, "C++ Game.gba"))
        assertNull(manager.createDocumentFile(row("Game%2Fother.gba"), tree.toString(), folder))
    }

    @Test fun distinctIndexedPathsWithTheSameBasenameCannotReplaceEachOther() = fixture { dir, folder ->
        val first = row("first/Game.gba")
        val originalPart = requireNotNull(manager.createDocumentFile(first, tree.toString(), folder))
        fill(originalPart, "original")
        manager.commitDocumentFile(first, tree.toString(), folder, originalPart, hash("original"))
        val second = row("second/Game.gba")
        val part = requireNotNull(manager.createDocumentFile(second, tree.toString(), folder))
        fill(part, "different")
        assertThrows(Exception::class.java) { manager.commitDocumentFile(second, tree.toString(), folder, part, hash("different")) }
        assertEquals("original", read(dir, "Game.gba"))
    }

    @Test fun genericCreateCannotDeleteAnExistingFileAndBlockedFoldersDoNotRedirectWrites() = fixture { dir, folder ->
        write(dir, "Game.gba", "original")
        assertNull(StorageHelper.createFile(context, tree.toString(), folder, "Game.gba", overwrite = true))
        assertEquals("original", read(dir, "Game.gba"))
        write(dir, "blocked", "a file")
        assertNull(StorageHelper.createDirectory(dir, "blocked/child"))
        assertNull(StorageHelper.createDirectory(dir, "../outside"))
        assertEquals("a file", read(dir, "blocked"))
    }
}
