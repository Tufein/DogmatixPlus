package com.tufein.valkyr.util

/**
 * A small thread-safe map that keeps at most [maxEntries] entries and drops the oldest-inserted one
 * first (4.5.0: caches that used to grow for the whole run). Re-putting a key counts as new.
 */
class BoundedMap<K : Any, V : Any>(private val maxEntries: Int = DEFAULT_MAX) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map.remove(key); map[key] = value }
    }

    fun remove(key: K): V? = synchronized(map) { map.remove(key) }

    fun clear() = synchronized(map) { map.clear() }

    val size: Int get() = synchronized(map) { map.size }

    companion object {
        const val DEFAULT_MAX = 4096
    }
}
