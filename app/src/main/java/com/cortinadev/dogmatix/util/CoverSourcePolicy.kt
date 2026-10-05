package com.cortinadev.dogmatix.util

/** How long a found cover, or the lack of one, is trusted before looking again. Pure JVM for the tests. */
object CoverSourcePolicy {
    /** A found cover is kept for a month (libretro renames now and then). */
    const val HIT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    /** A game without a cover is looked up again after three days (new box art, a new source). */
    const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000

    fun isFresh(url: String, fetchedAt: Long, now: Long): Boolean {
        val age = now - fetchedAt
        if (age < 0) return false
        return if (url.isEmpty()) age < MISS_TTL_MS else age < HIT_TTL_MS
    }
}
