package com.cortinadev.dogmatix.util

/**
 * Hands out each name of a growing set once. The library index used to look up every finished
 * download again each time one more finished: with thousands of finished rows that was millions of
 * database lookups after a big batch, enough to starve the UI thread.
 *
 * A name that leaves the set (its row was removed or downloaded again) is forgotten, so it counts
 * as new when it comes back.
 */
class FreshNames {
    private val seen = HashSet<String>()

    /** The names of [current] not handed out before, in the order of [current]. */
    @Synchronized
    fun next(current: Collection<String>): List<String> {
        val now = current as? Set<String> ?: current.toHashSet()
        seen.retainAll(now)
        return current.filter { seen.add(it) }
    }
}
