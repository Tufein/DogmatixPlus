package com.cortinadev.dogmatix.data.model

/** A retry waiting in this process. The monotonic deadline is never saved or shared with another device. */
data class PendingAutoRetry(
    val attempt: Int,
    val maxAttempts: Int,
    val deadlineElapsedRealtimeMillis: Long
) {
    /** Round up: a positive remaining wait never looks ready before the timer can run. */
    fun remainingSeconds(elapsedRealtimeMillis: Long): Long {
        val now = elapsedRealtimeMillis.coerceAtLeast(0L)
        if (now >= deadlineElapsedRealtimeMillis) return 0L
        val millis = deadlineElapsedRealtimeMillis - now
        return millis / 1_000L + if (millis % 1_000L == 0L) 0L else 1L
    }
}
