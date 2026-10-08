package com.cortinadev.dogmatix

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.*
import com.cortinadev.dogmatix.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SafRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
    private fun fixture(test: suspend (DocumentFile, DocumentFile, VerifiedDocumentCopy) -> Unit) = runBlocking {
        val base = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        val root = requireNotNull(base.createDirectory(UUID.randomUUID().toString()))
        try {
            context.contentResolver.call(tree, "fixture:fail_read", null, null)
            test(requireNotNull(root.createDirectory("source")), requireNotNull(root.createDirectory("target")), VerifiedDocumentCopy(context))
        } finally { context.contentResolver.call(tree, "fixture:fail_read", null, null); root.delete() }
    }
    private fun write(parent: DocumentFile, name: String, text: String): DocumentFile = requireNotNull(parent.createFile("application/octet-stream", name)).also {
        context.contentResolver.openOutputStream(it.uri, "wt")!!.use { stream -> stream.write(text.toByteArray()) }
    }
    @Test fun conflictingSameSizeFileIsPreservedOnSaf() = fixture { source, target, copier ->
        val original = write(source,"Game.gba","original")
        val existing = write(target,"Game.gba","modified")
        var refused = false
        try { copier.copy(original.uri, target, "Game.gba") } catch (_: Exception) { refused = true }
        assertTrue(refused)
        assertEquals("modified", StorageHelper.readText(context,existing))
        assertEquals("original", StorageHelper.readText(context,original))
    }
    @Test fun readFailureNeverRemovesExistingSafTarget() = fixture { source, target, copier ->
        val original = write(source,"Game.gba","original")
        val existing = write(target,"Game.gba","modified")
        context.contentResolver.call(tree,"fixture:fail_read","Game.gba",null)
        var refused = false
        try { copier.copy(original.uri,target,"Game.gba") } catch (_: Exception) { refused = true }
        assertTrue(refused)
        context.contentResolver.call(tree,"fixture:fail_read",null,null)
        assertEquals("modified",StorageHelper.readText(context,existing))
        assertTrue(original.exists())
    }
    @Test fun safTrashRoundTripPreservesSaveAndRestoresOriginalPath() = fixture { source, _, copier ->
        val original = write(source,"Game.gba","original")
        val save = write(source,"Game.srm","save progress")
        val history = OperationHistoryService(context)
        val trash = TrashService(context,copier,history,StorageMoveGate(),ActionLogService(context))
        assertEquals(1,trash.move(listOf(RemovalFile(original.uri.toString(),source.uri.toString(),"Game.gba",8)),"Game"))
        assertFalse(original.exists())
        assertEquals("save progress",StorageHelper.readText(context,save))
        val operation = history.entries.value.first { it.kind=="trash" && it.title=="Game" && it.phase=="stored" }
        // Strict deletion checks see recovery folders; game scans do not.
        val dir = requireNotNull(DiskScanner.rootOf(source.uri.toString()))
        assertTrue(DiskScanner.listOrNull(context,dir)!!.any { it.name==".dogmatix-trash" })
        assertFalse(DiskScanner.list(context,dir).any { it.name==".dogmatix-trash" })
        trash.restore(operation.id)
        assertEquals("original",StorageHelper.readText(context,source.findFile("Game.gba")!!))
        assertEquals("save progress",StorageHelper.readText(context,save))
    }
    @Test fun nestedExplorerTrashRestoresSameNamedFilesToTheirOwnFolders() = fixture { source, _, copier ->
        val one = requireNotNull(source.createDirectory("one"))
        val two = requireNotNull(source.createDirectory("two"))
        val first = write(one, "Game.sav", "first progress")
        val second = write(two, "Game.sav", "second progress")
        val history = OperationHistoryService(context)
        val trash = TrashService(context, copier, history, StorageMoveGate(), ActionLogService(context))
        val title = "nested-${UUID.randomUUID()}"
        assertEquals(2, trash.move(listOf(
            RemovalFile(first.uri.toString(), source.uri.toString(), "Game.sav", 14, "one/Game.sav", "one/Game.sav"),
            RemovalFile(second.uri.toString(), source.uri.toString(), "Game.sav", 15, "two/Game.sav", "two/Game.sav")
        ), title, directories = listOf(OperationDirectory(source.uri.toString(), "one"), OperationDirectory(source.uri.toString(), "two"))))
        assertNull(one.findFile("Game.sav"))
        assertNull(two.findFile("Game.sav"))
        val op = history.entries.value.first { it.title == title }
        assertEquals(2, op.files.map { it.target }.distinct().size)
        // Simulate folders being removed externally after their contents went to recovery.
        assertTrue(one.delete())
        assertTrue(two.delete())
        trash.restore(op.id)
        assertEquals("first progress", StorageHelper.readText(context, StorageHelper.findFile(source, "one/Game.sav")!!))
        assertEquals("second progress", StorageHelper.readText(context, StorageHelper.findFile(source, "two/Game.sav")!!))
    }

    @Test fun failedCopyLeavesAllNestedOriginalsAndSaveDataIntact() = fixture { source, _, copier ->
        val folder = requireNotNull(source.createDirectory("nested"))
        val first = write(folder, "First.gba", "first")
        val second = write(folder, "Fail.gba", "second")
        val save = write(folder, "Game.srm", "save progress")
        val history = OperationHistoryService(context)
        val trash = TrashService(context, copier, history, StorageMoveGate(), ActionLogService(context))
        context.contentResolver.call(tree, "fixture:fail_read", "Fail.gba", null)
        assertTrue(runCatching { trash.move(listOf(
            RemovalFile(first.uri.toString(), source.uri.toString(), "First.gba", 5, "nested/First.gba", "nested/First.gba"),
            RemovalFile(second.uri.toString(), source.uri.toString(), "Fail.gba", 6, "nested/Fail.gba", "nested/Fail.gba")
        ), "failed-nested") }.isFailure)
        context.contentResolver.call(tree, "fixture:fail_read", null, null)
        assertEquals("first", StorageHelper.readText(context, first))
        assertEquals("second", StorageHelper.readText(context, second))
        assertEquals("save progress", StorageHelper.readText(context, save))
    }

    @Test fun changedSourceCannotBeDeletedAfterVerifiedSafCopy() = fixture { source,target,copier ->
        val original=write(source,"Game.gba","original")
        val result=copier.copy(original.uri,target,"Game.gba")
        val hash=copier.hash(original.uri)
        context.contentResolver.openOutputStream(original.uri,"wt")!!.use { it.write("modified".toByteArray()) }
        assertFalse(copier.mayRemove(original.uri,result.uri,hash))
    }

    @Test fun partialRestoreVerifiesReturnedContentsAndReleasesDownloadGate() = fixture { source, _, copier ->
        val first = write(source, "First.gba", "first original")
        val second = write(source, "Second.gba", "second original")
        val history = OperationHistoryService(context)
        val gate = StorageMoveGate()
        val trash = TrashService(context, copier, history, gate, ActionLogService(context))
        val title = "partial-${UUID.randomUUID()}"
        trash.move(listOf(
            RemovalFile(first.uri.toString(), source.uri.toString(), "First.gba", 14),
            RemovalFile(second.uri.toString(), source.uri.toString(), "Second.gba", 15)
        ), title)
        val op = history.entries.value.first { it.title == title }
        val conflict = write(source, "Second.gba", "different save-safe contents")
        assertTrue(runCatching { trash.restore(op.id) }.isFailure)
        assertEquals(1, trash.restoredFiles(op.id))
        assertEquals("first original", StorageHelper.readText(context, source.findFile("First.gba")!!))
        assertEquals("different save-safe contents", StorageHelper.readText(context, conflict))
        assertTrue(gate.moving.value.isEmpty())
    }

    @Test fun missingTrashCopyDoesNotCountAsARecoveredFile() = fixture { source, _, copier ->
        val original = write(source, "Missing.gba", "original")
        val history = OperationHistoryService(context)
        val trash = TrashService(context, copier, history, StorageMoveGate(), ActionLogService(context))
        val title = "missing-${UUID.randomUUID()}"
        trash.move(listOf(RemovalFile(original.uri.toString(), source.uri.toString(), "Missing.gba", 8)), title)
        val op = history.entries.value.first { it.title == title }
        assertTrue(DocumentFile.fromSingleUri(context, Uri.parse(op.files.single().target))!!.delete())
        assertTrue(runCatching { trash.restore(op.id) }.isFailure)
        assertEquals(0, trash.restoredFiles(op.id))
        assertNull(source.findFile("Missing.gba"))
    }
}
