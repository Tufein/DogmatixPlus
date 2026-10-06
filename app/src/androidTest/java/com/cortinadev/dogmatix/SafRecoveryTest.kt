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
        val trash = TrashService(context,copier,history,StorageMoveGate())
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
    @Test fun changedSourceCannotBeDeletedAfterVerifiedSafCopy() = fixture { source,target,copier ->
        val original=write(source,"Game.gba","original")
        val result=copier.copy(original.uri,target,"Game.gba")
        val hash=copier.hash(original.uri)
        context.contentResolver.openOutputStream(original.uri,"wt")!!.use { it.write("modified".toByteArray()) }
        assertFalse(copier.mayRemove(original.uri,result.uri,hash))
    }
}
