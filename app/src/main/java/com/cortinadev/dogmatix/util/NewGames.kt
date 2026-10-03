package com.cortinadev.dogmatix.util

/** "New" in the library: files a rescan found during the last [DAYS] days. */
object NewGames {
    const val DAYS = 14

    fun since(now: Long): Long = now - DAYS * 24L * 3_600_000L

    /** True for a file a rescan (not a source's first scan) found within the window. */
    fun isNew(firstSeenAt: Long, now: Long): Boolean = firstSeenAt > 0 && firstSeenAt >= since(now)
}
