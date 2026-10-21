package com.tufein.valkyr.util

/**
 * Remembers what a directory listing returned for the length of one operation, so a loop over many
 * files does not ask the storage provider (one IPC call per listing on SAF) for the same folder
 * again for every file. The caller drops an entry ([invalidate]) when it changes that folder and
 * empties the cache ([clear]) before it needs a fresh view. Not thread-safe: one operation owns it.
 */
class ListingCache<K, V> {
    private val entries = HashMap<K, List<V>>()

    /** Number of times a listing was really read; for tests. */
    var loads = 0
        private set

    fun list(key: K, load: () -> List<V>): List<V> = entries.getOrPut(key) { loads++; load() }

    fun invalidate(key: K) { entries.remove(key) }

    fun clear() = entries.clear()
}
