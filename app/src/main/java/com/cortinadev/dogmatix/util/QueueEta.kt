package com.cortinadev.dogmatix.util

/**
 * What is left of the download queue and roughly how long it takes: the bytes still to fetch of
 * every queued or running download, at the speed the running ones reach together right now.
 */
object QueueEta {
    /** One download: bytes still to fetch, its speed in MiB/s (as the list shows it), and whether it runs. */
    class Item(val remainingBytes: Long, val mibPerSecond: Float, val running: Boolean)

    /** [seconds] is null while nothing transfers (all waiting, or the speed is not known yet). */
    data class Eta(val remainingBytes: Long, val bytesPerSecond: Long, val seconds: Long?)

    private const val MIB = 1_048_576L

    fun of(items: List<Item>): Eta {
        val remaining = items.sumOf { it.remainingBytes.coerceAtLeast(0) }
        val speed = items.filter { it.running }.sumOf { (it.mibPerSecond.coerceAtLeast(0f) * MIB).toLong() }
        val seconds = if (speed > 0 && remaining > 0) (remaining + speed - 1) / speed else null
        return Eta(remaining, speed, seconds)
    }

    /** Whole hours and the minutes after them, rounded up to the next minute (never "0 min"). */
    fun hoursMinutes(seconds: Long): Pair<Long, Long> {
        val minutes = ((seconds.coerceAtLeast(0) + 59) / 60).coerceAtLeast(1)
        return minutes / 60 to minutes % 60
    }
}
