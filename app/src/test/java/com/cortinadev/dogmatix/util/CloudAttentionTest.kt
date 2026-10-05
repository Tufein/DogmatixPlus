package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAttentionTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun `nothing needs a look while no server is set up`() {
        assertEquals(0, CloudAttention.count(false, "dav|AUTH|401|", "k|NO_PASSPHRASE", "k|SYNC_NEWER", 5))
    }

    @Test fun `a clean cloud needs nothing`() {
        assertEquals(0, CloudAttention.count(true, "", "", "", 0))
    }

    @Test fun `each problem counts once`() {
        assertEquals(1, CloudAttention.count(true, "dav|AUTH|401|", "", "", 0))
        assertEquals(1, CloudAttention.count(true, "", "dav|NO_SPACE|507|", "", 0))
        assertEquals(1, CloudAttention.count(true, "", "", "dav|NETWORK|0|", 0))
        // Fifty held-back removals are still one thing to look at.
        assertEquals(1, CloudAttention.count(true, "", "", "", 50))
        assertEquals(4, CloudAttention.count(true, "a", "b", "c", 1))
    }

    @Test fun `a backup is stale only after three days of automatic backup`() {
        assertFalse(CloudAttention.backupStale(false, now - 10 * day, now))
        assertFalse(CloudAttention.backupStale(true, 0L, now))
        assertFalse(CloudAttention.backupStale(true, now - 2 * day, now))
        assertFalse(CloudAttention.backupStale(true, now - CloudAttention.STALE_DAYS * day, now))
        assertTrue(CloudAttention.backupStale(true, now - CloudAttention.STALE_DAYS * day - 1, now))
        assertTrue(CloudAttention.backupStale(true, now - 30 * day, now))
    }
}
