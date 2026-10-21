package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedMapTest {
    @Test fun evictsTheOldestEntryOverTheLimit() {
        val map = BoundedMap<String, Int>(3)
        listOf("a", "b", "c", "d").forEachIndexed { i, k -> map[k] = i }
        assertEquals(3, map.size)
        assertNull(map["a"])
        assertEquals(3, map["d"])
    }

    @Test fun rePuttingAKeyMakesItNewest() {
        val map = BoundedMap<String, Int>(2)
        map["a"] = 1; map["b"] = 2; map["a"] = 3; map["c"] = 4
        assertNull(map["b"])
        assertEquals(3, map["a"])
        assertEquals(4, map["c"])
    }

    @Test fun removeAndClear() {
        val map = BoundedMap<String, Long>(5)
        map["x"] = 1L
        assertEquals(1L, map.remove("x"))
        assertNull(map.remove("x"))
        map["y"] = 2L
        map.clear()
        assertEquals(0, map.size)
    }

    @Test fun defaultLimitIsBounded() {
        val map = BoundedMap<Int, Int>()
        repeat(BoundedMap.DEFAULT_MAX + 100) { map[it] = it }
        assertEquals(BoundedMap.DEFAULT_MAX, map.size)
        assertNull(map[0])
    }
}
