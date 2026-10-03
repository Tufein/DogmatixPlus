package com.cortinadev.dogmatix.util

/** When and how the background save sync runs. Pure choices; the job scheduling lives in the service. */
object BackgroundSyncPolicy {
    /** Offered intervals in hours. */
    val intervals = listOf(1, 3, 6, 12, 24)
    const val DEFAULT_INTERVAL_H = 6
    /** Android does not run periodic jobs more often than this. */
    private const val MIN_PERIOD_MS = 15L * 60 * 1000

    fun periodMillis(hours: Int): Long = maxOf(MIN_PERIOD_MS, hours.coerceAtLeast(1) * 60L * 60 * 1000)

    /** The next / previous offered interval, wrapping around; an unknown value counts as the default. */
    fun cycle(current: Int, delta: Int): Int {
        val index = intervals.indexOf(current).takeIf { it >= 0 } ?: intervals.indexOf(DEFAULT_INTERVAL_H)
        return intervals[((index + delta) % intervals.size + intervals.size) % intervals.size]
    }

    /** A notification is only worth it when the user has to do something or something broke. */
    fun shouldNotify(conflicts: Int, failed: Int): Boolean = conflicts > 0 || failed > 0

    /** The background run is allowed to start: set up, and (per setting) on an unmetered network / charging. */
    fun mayRun(enabled: Boolean, configured: Boolean): Boolean = enabled && configured
}
