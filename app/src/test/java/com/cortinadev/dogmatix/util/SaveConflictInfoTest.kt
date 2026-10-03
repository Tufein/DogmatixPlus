package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SaveConflictInfoTest {
    private val t0 = 1_700_000_000_000L
    private fun local(size: Long, modified: Long) = LocalSaveFile(SaveKind.SAVE, "g.srm", size, modified)
    private fun remote(size: Long, at: Long) = RemoteSaveFile(SaveKind.SAVE, 1, 1, "g.srm", null, Instant.ofEpochMilli(at).toString(), size, "")

    @Test fun `the device copy is newer`() {
        val info = SaveConflictInfo.of(local(100, t0 + 60_000), remote(120, t0))
        assertEquals(NewerSide.DEVICE, info.newer)
        assertEquals(20L, info.sizeDelta)
        assertEquals(t0, info.serverModified)
    }

    @Test fun `the server copy is newer`() {
        assertEquals(NewerSide.SERVER, SaveConflictInfo.of(local(100, t0), remote(100, t0 + 3_600_000)).newer)
    }

    @Test fun `times a moment apart count as the same`() {
        val info = SaveConflictInfo.of(local(64, t0), remote(64, t0 + 1_500))
        assertEquals(NewerSide.SAME, info.newer)
        assertTrue(info.sameSize)
    }

    @Test fun `a missing time is unknown rather than guessed`() {
        val info = SaveConflictInfo.of(local(10, 0), remote(10, t0))
        assertEquals(NewerSide.UNKNOWN, info.newer)
        assertNull(info.deviceModified)
    }

    @Test fun `a much smaller server copy shows as a negative delta`() {
        assertEquals(-8_000L, SaveConflictInfo.of(local(32_768, t0), remote(24_768, t0 + 10_000)).sizeDelta)
    }
}
