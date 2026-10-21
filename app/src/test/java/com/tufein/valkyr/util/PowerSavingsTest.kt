package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavingsTest {
    @Test fun listingCacheReadsAFolderOnce() {
        val cache = ListingCache<String, String>()
        var reads = 0
        repeat(5) { cache.list("a") { reads++; listOf("x", "y") } }
        cache.list("b") { reads++; emptyList() }
        assertEquals(2, reads)
        assertEquals(2, cache.loads)
        assertEquals(listOf("x", "y"), cache.list("a") { error("must be cached") })
    }

    @Test fun listingCacheReadsAgainAfterInvalidateOrClear() {
        val cache = ListingCache<String, Int>()
        var version = 0
        fun read() = cache.list("a") { listOf(++version) }
        assertEquals(listOf(1), read())
        cache.invalidate("a")
        assertEquals(listOf(2), read())
        cache.clear()
        assertEquals(listOf(3), read())
        cache.invalidate("unknown")
    }

    @Test fun clockAndSpaceArePolledOnlyWhenARuleNeedsThem() {
        assertFalse(DownloadRules().needsPolling)
        assertFalse(DownloadRules(wifiOnly = true, chargingOnly = true).needsPolling)
        assertTrue(DownloadRules(nightOnly = true).needsPolling)
        assertTrue(DownloadRules(minFreeBytes = 1L).needsPolling)
    }

    @Test fun downloadBufferIsLargeButBounded() {
        assertTrue(Constants.BUFFER_SIZE in 64 * 1024..256 * 1024)
    }
}
