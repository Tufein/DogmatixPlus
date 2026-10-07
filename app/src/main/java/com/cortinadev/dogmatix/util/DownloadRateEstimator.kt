package com.cortinadev.dogmatix.util

import kotlin.math.exp

/**
 * One small estimator per transferring file. Times must come from a monotonic clock, so changing
 * the device's date cannot produce a rate spike. A stalled source loses its old rate after five
 * seconds, even if native callbacks keep repeating the last positive rate.
 */
class DownloadRateEstimator {
    private var previousBytes: Long? = null
    private var sampledAt = 0L
    private var advancedAt = 0L
    private var smoothed = 0f

    val hasRate: Boolean get() = smoothed > 0f

    fun record(downloadedBytes: Long, mibPerSecond: Float, nowMs: Long): Float {
        val bytes = downloadedBytes.coerceAtLeast(0L)
        val previous = previousBytes
        val validRate = mibPerSecond.takeIf { it.isFinite() && it > 0f } ?: 0f
        if (previous == null || bytes < previous || nowMs < sampledAt) {
            smoothed = if (bytes > 0L) validRate else 0f
            advancedAt = nowMs
        } else {
            if (bytes > previous) advancedAt = nowMs
            val elapsed = (nowMs - sampledAt).coerceAtLeast(0L)
            val weight = (1.0 - exp(-elapsed.toDouble() / SMOOTHING_MS)).toFloat()
            smoothed = when {
                bytes == 0L -> 0f
                nowMs - advancedAt >= STALE_AFTER_MS -> 0f
                smoothed <= 0f -> validRate
                else -> smoothed + weight * (validRate - smoothed)
            }
        }
        previousBytes = bytes
        sampledAt = nowMs
        return current(nowMs)
    }

    fun current(nowMs: Long): Float {
        if (nowMs < advancedAt || nowMs - advancedAt >= STALE_AFTER_MS) smoothed = 0f
        return smoothed
    }

    companion object {
        const val STALE_AFTER_MS = 5_000L
        private const val SMOOTHING_MS = 3_000.0
    }
}
