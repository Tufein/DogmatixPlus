package com.cortinadev.dogmatix

import androidx.test.platform.app.InstrumentationRegistry
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.service.VerifiedDocumentCopy
import com.cortinadev.dogmatix.data.service.OperationHistoryService
import com.cortinadev.dogmatix.data.service.LibraryOperation
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android stream/rename behavior; SAF provider coverage is tracked separately. */
class VerifiedDocumentCopyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: (File, File, VerifiedDocumentCopy) -> Unit) {
        val root = File(context.cacheDir, "copy-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(File(root, "source.gba").apply { writeText("original") }, File(root, "target").apply { mkdirs() }, VerifiedDocumentCopy(context)) }
        finally { root.deleteRecursively() }
    }
    @Test fun copiesAndVerifiesWithoutDeletingOriginal() = fixture { source, target, copier ->
        val result = runBlocking { copier.copy(source.toUri(), DocumentFile.fromFile(target), "Game.gba") }
        assertEquals("original", File(target, "Game.gba").readText())
        assertTrue(source.exists())
        assertTrue(copier.mayRemove(source.toUri(), result.uri, copier.hash(source.toUri())))
    }
    @Test fun sameSizeConflictPreservesBothVersions() = fixture { source, target, copier ->
        File(target, "Game.gba").writeText("modified")
        assertThrows(Exception::class.java) { runBlocking { copier.copy(source.toUri(), DocumentFile.fromFile(target), "Game.gba") } }
        assertEquals("modified", File(target, "Game.gba").readText())
        assertEquals("original", source.readText())
    }
    @Test fun unreadableSourceNeverDeletesExistingTarget() = fixture { source, target, copier ->
        File(target, "Game.gba").writeText("modified")
        source.delete()
        assertThrows(Exception::class.java) { runBlocking { copier.copy(source.toUri(), DocumentFile.fromFile(target), "Game.gba") } }
        assertEquals("modified", File(target, "Game.gba").readText())
    }
    @Test fun sameContentTargetCanBeReusedAndModificationBlocksCleanup() = fixture { source, target, copier ->
        File(target, "Game.gba").writeText("original")
        val result = runBlocking { copier.copy(source.toUri(), DocumentFile.fromFile(target), "Game.gba") }
        val expected = copier.hash(source.toUri())
        source.writeText("modified")
        assertFalse(copier.mayRemove(source.toUri(), result.uri, expected))
    }
    @Test fun journalSurvivesServiceRecreation() {
        val operation = LibraryOperation(kind="test", title="", phase="interrupted")
        OperationHistoryService(context).put(operation)
        assertEquals(operation, OperationHistoryService(context).get(operation.id))
        OperationHistoryService(context).put(operation.copy(phase="done"))
    }
}
