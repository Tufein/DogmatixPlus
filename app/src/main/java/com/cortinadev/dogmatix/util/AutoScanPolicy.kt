package com.cortinadev.dogmatix.util

/** When the background scan may run; the job itself wakes up every hour (when its conditions hold) and asks. */
object AutoScanPolicy {

    /** How often the job wakes up to ask [isDue]. */
    const val CHECK_EVERY_MS = 60 * 60 * 1000L

    /** Intervals offered in Settings, in hours. */
    val INTERVALS = listOf(12, 24, 48, 168)

    /**
     * Due when the last scan is [hours] ago (with an hour's slack, so a daily scan does not slide
     * later every day) and, with [nightOnly], it is inside the night window.
     */
    fun isDue(now: Long, last: Long, hours: Int, nightOnly: Boolean, minuteOfDay: Int, nightStart: Int, nightEnd: Int): Boolean {
        if (nightOnly && !DownloadPolicy.inWindow(minuteOfDay, nightStart, nightEnd)) return false
        val interval = hours.coerceAtLeast(1) * 3_600_000L
        return now - last >= interval - CHECK_EVERY_MS
    }
}
