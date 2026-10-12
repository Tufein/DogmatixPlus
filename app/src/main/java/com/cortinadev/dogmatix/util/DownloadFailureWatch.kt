package com.cortinadev.dogmatix.util

/**
 * Which downloads just failed, from successive snapshots of the Downloads list. A StateFlow drops
 * snapshots it has no time to deliver, so "was seen running before" misses a download that
 * failed quickly; instead every failure not yet recorded counts, once. The first snapshot only
 * primes: its failures are from an earlier run (restored from the database), not news. A download
 * that leaves FAILED (a retry) and fails again is a new failure. Not thread-safe; the caller
 * synchronizes.
 */
class DownloadFailureWatch {
    private val recorded = HashSet<String>()
    private var primed = false

    /** [items]: each download's file name and whether it is FAILED now. Returns the names of new failures, in list order. */
    fun update(items: List<Pair<String, Boolean>>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for ((name, failed) in items) {
            seen += name
            if (!failed) { recorded -= name; continue }
            if (recorded.add(name) && primed) out += name
        }
        recorded.retainAll(seen)
        primed = true
        return out
    }
}
