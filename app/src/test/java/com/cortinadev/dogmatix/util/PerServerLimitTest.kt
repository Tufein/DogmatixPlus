package com.cortinadev.dogmatix.util

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PerServerLimitTest {
    @Test fun `per-server limit lets downloads of other servers go first`() = runBlocking {
        val queue = DownloadQueue(slots = 3, perHost = 1)
        val order = mutableListOf<String>()
        queue.acquire("a1", "a.example")
        val a2 = async { queue.acquire("a2", "a.example"); order += "a2" }
        val b1 = async { queue.acquire("b1", "b.example"); order += "b1" }
        repeat(5) { yield() }
        assertEquals(listOf("b1"), order)               // a2 waits for a.example although it queued first
        assertEquals(listOf("a2"), queue.waiting.value)
        queue.release("a.example")
        a2.await(); b1.await()
        assertEquals(listOf("b1", "a2"), order)
        queue.setPerHost(0)
        val c = async { queue.acquire("a3", "a.example"); order += "a3" }
        c.await()
        assertEquals("a3", order.last())
    }
}
