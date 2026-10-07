package com.cortinadev.dogmatix.data.service

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMetadataCancellationTest {
    @Test fun `canceling unfinished metadata removes the uncached resource once`() = runBlocking {
        val handle = Any()
        val waiting = CompletableDeferred<Unit>()
        val discarded = ArrayList<Any>()
        val fetch = launch(start = CoroutineStart.UNDISPATCHED) {
            withMetadataPreflight(handle, newlyAdded = true, discard = { discarded += it }) {
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { waiting.await() }
        withTimeout(2_000) { fetch.cancelAndJoin() }
        assertEquals(1, discarded.size)
        assertSame(handle, discarded.single())
        assertTrue(fetch.isCancelled)
    }

    @Test fun `canceling a fetch that reused a sibling handle preserves that handle`() = runBlocking {
        var discarded = false
        val fetch = launch(start = CoroutineStart.UNDISPATCHED) {
            withMetadataPreflight(Any(), newlyAdded = false, discard = { discarded = true }) {
                awaitCancellation()
            }
        }
        withTimeout(2_000) { fetch.cancelAndJoin() }
        assertEquals(false, discarded)
    }

    @Test fun `completed metadata keeps the resource for cache publication`() = runBlocking {
        val handle = Any()
        var discarded = false
        val ready = withMetadataPreflight(handle, newlyAdded = true, discard = { discarded = true }) { it }
        assertSame(handle, ready)
        assertEquals(false, discarded)
    }

    @Test fun `failed cleanup preserves the original metadata failure`() = runBlocking {
        val failure = IOException("metadata unavailable")
        val cleanup = IllegalStateException("handle already gone")
        try {
            withMetadataPreflight(Any(), newlyAdded = true, discard = { throw cleanup }) { throw failure }
            throw AssertionError("Expected metadata failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            assertSame(cleanup, actual.suppressed.single())
        }
    }
}
