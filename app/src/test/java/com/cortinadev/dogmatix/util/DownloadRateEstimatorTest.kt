package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRateEstimatorTest {
    @Test fun `first transfer sample is available immediately then a burst is smoothed`() {
        val estimator = DownloadRateEstimator()
        assertEquals(2f, estimator.record(2_097_152L, 2f, 1_000L), 0f)
        val burst = estimator.record(12_582_912L, 10f, 2_000L)
        assertTrue(burst > 2f && burst < 5f)
    }

    @Test fun `stalled connection loses speed without another progress callback`() {
        val estimator = DownloadRateEstimator()
        estimator.record(1_048_576L, 1f, 1_000L)
        assertEquals(1f, estimator.current(5_999L), 0f)
        assertEquals(0f, estimator.current(6_000L), 0f)
    }

    @Test fun `repeated native rates cannot keep a stalled file alive`() {
        val estimator = DownloadRateEstimator()
        estimator.record(1_048_576L, 1f, 1_000L)
        for (now in 2_000L..5_000L step 1_000L) estimator.record(1_048_576L, 1f, now)
        assertEquals(0f, estimator.record(1_048_576L, 1f, 6_000L), 0f)
        assertEquals(0f, estimator.record(1_048_576L, 1f, 7_000L), 0f)
        assertEquals(2f, estimator.record(3_145_728L, 2f, 8_000L), 0f)
    }

    @Test fun `resuming a partial file does not turn its existing bytes into a rate`() {
        val estimator = DownloadRateEstimator()
        assertEquals(0f, estimator.record(100_000_000L, 0f, 1_000L), 0f)
        assertEquals(1f, estimator.record(101_048_576L, 1f, 2_000L), 0f)
    }

    @Test fun `reset byte counter starts a fresh estimate`() {
        val estimator = DownloadRateEstimator()
        estimator.record(100_000_000L, 20f, 1_000L)
        assertEquals(1f, estimator.record(1_048_576L, 1f, 2_000L), 0f)
    }

    @Test fun `clock reversal and invalid rates cannot produce negative or infinite speeds`() {
        val estimator = DownloadRateEstimator()
        estimator.record(1_048_576L, 1f, 2_000L)
        assertEquals(0f, estimator.current(1_000L), 0f)
        assertEquals(0f, estimator.record(2_097_152L, Float.NaN, 1_000L), 0f)
        assertEquals(0f, estimator.record(3_145_728L, Float.POSITIVE_INFINITY, 2_000L), 0f)
        assertEquals(0f, estimator.record(4_194_304L, -1f, 3_000L), 0f)
    }

    @Test fun `unchanged byte count cannot invent speed at startup`() {
        val estimator = DownloadRateEstimator()
        assertEquals(0f, estimator.record(0L, 3f, 1_000L), 0f)
    }
}
