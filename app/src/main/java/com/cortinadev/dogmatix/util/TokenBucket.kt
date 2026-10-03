package com.cortinadev.dogmatix.util

/**
 * Bandwidth shared by every download: each chunk takes its bytes out of one bucket that refills at
 * [bytesPerSecond] (at most one second's worth in reserve). A chunk that overdraws the bucket is
 * told how long to wait, so three downloads together stay under the limit instead of each one.
 * Not thread-safe on its own; callers lock around [take].
 */
class TokenBucket(private val clock: () -> Long = System::currentTimeMillis) {
    /** 0 or less = unlimited. */
    var bytesPerSecond: Long = 0
        set(value) {
            if (value != field) { field = value; available = value.toDouble().coerceAtLeast(0.0); last = clock() }
        }

    private var available = 0.0
    private var last = clock()

    /** Milliseconds to wait before [bytes] may be used; 0 when unlimited or in budget. */
    fun take(bytes: Int): Long {
        val rate = bytesPerSecond
        if (rate <= 0) return 0
        val now = clock()
        available = minOf(rate.toDouble(), available + (now - last) * rate / 1000.0)
        last = now
        available -= bytes
        return if (available >= 0) 0 else Math.ceil(-available * 1000.0 / rate).toLong()
    }
}

object SpeedLimit {
    /**
     * The limit to apply now, in bytes per second: none when the setting is off (infinite / 0), or
     * when it is lifted at night and it is night. The setting is in KB/s (Settings → speed limit).
     */
    fun effectiveBytesPerSecond(limitKBs: Float, dayOnly: Boolean, minuteOfDay: Int, nightStart: Int, nightEnd: Int): Long {
        if (limitKBs.isInfinite() || limitKBs <= 0f) return 0
        if (dayOnly && DownloadPolicy.inWindow(minuteOfDay, nightStart, nightEnd)) return 0
        return (limitKBs.toDouble() * 1024).toLong().coerceAtLeast(1)
    }
}
