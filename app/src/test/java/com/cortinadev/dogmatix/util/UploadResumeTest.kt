package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadResumeTest {
    private val now = 1_700_000_000_000L
    private fun session(next: Int = 3, at: Long = now, chunks: Int = 10) =
        UploadSession("Game.zip", 5, "Game.zip", 80_000_000, chunks, "up-1", next, at)

    @Test fun `sessions survive an encode and decode`() {
        val decoded = UploadSessions.decode(UploadSessions.encode(listOf(session(), session(next = 1).copy(uploadId = "up-2", fileName = "B.zip"))))
        assertEquals(2, decoded.size)
        assertEquals("up-1", decoded[0].uploadId)
        assertEquals(3, decoded[0].nextChunk)
        assertEquals("B.zip", decoded[1].fileName)
    }

    @Test fun `damaged files and impossible sessions give nothing to resume`() {
        assertTrue(UploadSessions.decode("not json").isEmpty())
        assertTrue(UploadSessions.decode("{}").isEmpty())
        val bad = UploadSessions.encode(listOf(session(next = 10), session().copy(uploadId = "")))
        assertTrue(UploadSessions.decode(bad).isEmpty())
    }

    @Test fun `a session is picked for the same file, size and chunking`() {
        val list = listOf(session())
        assertNotNull(UploadSessions.resumable(list, 5, "Game.zip", 80_000_000, 10, now + 1_000))
        assertNull(UploadSessions.resumable(list, 6, "Game.zip", 80_000_000, 10, now))        // other platform
        assertNull(UploadSessions.resumable(list, 5, "Game.zip", 80_000_001, 10, now))        // file changed
        assertNull(UploadSessions.resumable(list, 5, "Game.zip", 80_000_000, 12, now))        // chunking changed
    }

    @Test fun `old or not yet started sessions are not resumed`() {
        assertNull(UploadSessions.resumable(listOf(session(at = now - UploadSessions.MAX_AGE_MS - 1)), 5, "Game.zip", 80_000_000, 10, now))
        assertNull(UploadSessions.resumable(listOf(session(next = 0)), 5, "Game.zip", 80_000_000, 10, now))
    }

    @Test fun `offsets and failure kinds`() {
        assertEquals(3L * 8 * 1024 * 1024, UploadSessions.offsetOf(3, 8 * 1024 * 1024))
        assertTrue(UploadSessions.isTransient(null))
        assertTrue(UploadSessions.isTransient(503))
        assertTrue(UploadSessions.isTransient(429))
        assertFalse(UploadSessions.isTransient(404))
        assertFalse(UploadSessions.isTransient(400))
    }
}
