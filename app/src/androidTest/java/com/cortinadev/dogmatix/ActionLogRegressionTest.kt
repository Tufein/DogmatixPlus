package com.cortinadev.dogmatix

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.ActionLogService
import com.cortinadev.dogmatix.util.ActionEntry
import com.cortinadev.dogmatix.util.ActionKind
import com.cortinadev.dogmatix.util.ActionLogFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated private files: disk failures must not make explicit privacy actions look successful. */
class ActionLogRegressionTest {
    private fun fixture(test: suspend (File, ActionLogService) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "action-log-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
        try { test(root, ActionLogService(context)) } finally { root.deleteRecursively() }
    }

    @Test fun failedClearKeepsVisibleHistoryAndReportsFailure() = fixture { root, log ->
        log.record(ActionKind.PLAYED, title = "Private game")
        val before = withTimeout(5_000) { log.entries.first { it?.size == 1 }!! }
        // A nonempty directory at the file path cannot be silently removed by clear().
        val file = File(root, "action_log.jsonl")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "blocked").writeText("keep")
        assertTrue(withTimeout(5_000) { runCatching { log.clear() }.isFailure })
        assertEquals(before, log.entries.value)
        assertTrue(File(file, "blocked").exists())
        assertTrue(file.deleteRecursively())
        log.clear()
        withTimeout(5_000) { log.entries.first { it?.isEmpty() == true } }
        assertFalse(file.exists())
    }

    @Test fun failedUndoRewriteDoesNotHideTheUndoButtonOrChangeDisk() = fixture { root, log ->
        log.record(ActionKind.DOWNLOAD_FAILED, title = "Game", consoleId = "gba", fileName = "Game.zip")
        val before = withTimeout(5_000) { log.entries.first { it?.size == 1 }!! }
        // Force only the atomic rewrite to fail; the existing durable history is untouched.
        val staging = File(root, "action_log.jsonl.tmp").apply { mkdirs() }
        File(staging, "blocked").writeText("keep")
        assertTrue(withTimeout(5_000) { runCatching { log.markUndone(before.single().id) }.isFailure })
        assertEquals(before, log.entries.value)
        assertEquals(before, ActionLogFile(File(root, "action_log.jsonl")).load())
    }

    @Test fun removalLookupWaitsForAQueuedWriteAndRetainsDownloadAgainIdentity() = fixture { _, log ->
        val operation = UUID.randomUUID().toString()
        log.record(ActionKind.REMOVED, title = "Game", consoleId = "gba", fileName = "Game.zip", opId = operation)
        val removal = withTimeout(5_000) { log.removal(operation) }
        assertNotNull(removal)
        assertEquals("gba", removal!!.consoleId)
        assertEquals("Game.zip", removal.fileName)
    }

    @Test fun restartWithClockBehindKeepsHistoryTimeOrder() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "action-log-clock-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
        try {
            val future = System.currentTimeMillis() + 86_400_000L
            ActionLogFile(File(root, "action_log.jsonl")).append(ActionEntry("previous", future, ActionKind.PLAYED, "Before restart"))
            val log = ActionLogService(context)
            log.record(ActionKind.PLAYED, title = "After restart")
            val lines = withTimeout(5_000) { log.entries.first { it?.size == 2 }!! }
            assertEquals(listOf("Before restart", "After restart"), lines.map { it.title })
            assertTrue(lines[1].at >= lines[0].at)
            assertEquals(lines, ActionLogFile(File(root, "action_log.jsonl")).load())
        } finally { root.deleteRecursively() }
    }
}
