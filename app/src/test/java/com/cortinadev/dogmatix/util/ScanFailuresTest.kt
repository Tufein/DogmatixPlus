package com.cortinadev.dogmatix.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ScanFailuresTest {
    @Test fun `http codes map to kinds`() {
        assertEquals(FailureKind.RATE_LIMITED, ScanFailures.kindOf(ScrapeHttpException(429, 5, "u")))
        assertEquals(FailureKind.SERVER_ERROR, ScanFailures.kindOf(ScrapeHttpException(503, null, "u")))
        assertEquals(FailureKind.NOT_FOUND, ScanFailures.kindOf(ScrapeHttpException(404, null, "u")))
        assertEquals(FailureKind.FORBIDDEN, ScanFailures.kindOf(ScrapeHttpException(403, null, "u")))
        assertEquals(429, ScanFailures.httpCodeOf(RuntimeException(ScrapeHttpException(429, null, "u"))))
    }

    @Test fun `other errors map to kinds, also when wrapped`() {
        assertEquals(FailureKind.TIMEOUT, ScanFailures.kindOf(SocketTimeoutException("read timed out")))
        assertEquals(FailureKind.NETWORK, ScanFailures.kindOf(RuntimeException(UnknownHostException("x"))))
        assertEquals(FailureKind.NO_TABLE, ScanFailures.kindOf(NoFileTableException("u")))
        assertEquals(FailureKind.OTHER, ScanFailures.kindOf(IllegalStateException("?")))
        assertNull(ScanFailures.httpCodeOf(IllegalStateException("?")))
    }

    @Test fun `only passing trouble is retried`() {
        assertTrue(ScanFailures.isRetryable(FailureKind.RATE_LIMITED))
        assertTrue(ScanFailures.isRetryable(FailureKind.SERVER_ERROR))
        assertTrue(ScanFailures.isRetryable(FailureKind.TIMEOUT))
        assertFalse(ScanFailures.isRetryable(FailureKind.NOT_FOUND))
        assertFalse(ScanFailures.isRetryable(FailureKind.FORBIDDEN))
        assertFalse(ScanFailures.isRetryable(FailureKind.NO_TABLE))
    }

    @Test fun `backoff grows, follows Retry-After and is capped`() {
        assertEquals(3_000L, ScanFailures.backoffMillis(1, null))
        assertEquals(6_000L, ScanFailures.backoffMillis(2, null))
        assertEquals(24_000L, ScanFailures.backoffMillis(4, null))
        assertEquals(48_000L, ScanFailures.backoffMillis(9, null))
        assertEquals(10_000L, ScanFailures.backoffMillis(1, 10))
        assertEquals(120_000L, ScanFailures.backoffMillis(1, 3600))
    }

    @Test fun `Retry-After as seconds or a date`() {
        assertEquals(30L, ScanFailures.parseRetryAfter("30"))
        val now = 1_700_000_000_000L
        assertEquals(60L, ScanFailures.parseRetryAfter("Tue, 14 Nov 2023 22:14:20 GMT", now))
        assertNull(ScanFailures.parseRetryAfter("soon"))
        assertNull(ScanFailures.parseRetryAfter(null))
    }

    @Test fun `a gate runs requests side by side until the server pushes back`() = runBlocking {
        val gate = HostGate(parallel = 2, pauseMs = 0, slowPauseMs = 0)
        val running = AtomicInteger(); var peak = 0
        suspend fun work() = gate.run { val n = running.incrementAndGet(); synchronized(this@ScanFailuresTest) { peak = maxOf(peak, n) }; delay(50); running.decrementAndGet() }
        (1..6).map { async { work() } }.awaitAll()
        assertEquals(2, peak)

        gate.pushBack(0)
        assertTrue(gate.slowedDown)
        peak = 0
        (1..4).map { async { work() } }.awaitAll()
        assertEquals(1, peak)
    }

    @Test fun `a pushed back gate waits until the named moment`() = runBlocking {
        var now = 0L
        val gate = HostGate(1, 0, 0, clock = { now })
        gate.pushBack(80)
        val started = System.nanoTime()
        gate.run { }
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 70)
    }
}
