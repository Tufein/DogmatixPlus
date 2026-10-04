package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshNamesTest {
    @Test fun `each name comes out once`() {
        val fresh = FreshNames()
        assertEquals(listOf("a", "b"), fresh.next(linkedSetOf("a", "b")))
        assertEquals(listOf("c"), fresh.next(linkedSetOf("a", "b", "c")))
        assertEquals(emptyList<String>(), fresh.next(linkedSetOf("a", "b", "c")))
    }

    @Test fun `a name that left and came back is new again`() {
        val fresh = FreshNames()
        fresh.next(setOf("a", "b"))
        assertEquals(emptyList<String>(), fresh.next(setOf("b")))
        assertEquals(listOf("a"), fresh.next(linkedSetOf("a", "b")))
    }

    @Test fun `thousands of finished downloads cost one pass each`() {
        val fresh = FreshNames()
        val names = LinkedHashSet<String>()
        var handedOut = 0
        repeat(5_000) { i ->
            names += "game $i.zip"
            handedOut += fresh.next(names).size
        }
        assertEquals(5_000, handedOut)
    }
}
