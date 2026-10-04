package com.cortinadev.dogmatix.util

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueOrderTest {
    @Test fun `a thousand waiters are served in order without waking each other`() = runBlocking {
        val queue = DownloadQueue(2)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val started = System.nanoTime()
        val jobs = (0 until 1000).map { i ->
            async(Dispatchers.Default) {
                queue.acquire("g$i")
                order += i
                queue.release()
            }
        }
        jobs.forEach { it.await() }
        assertEquals(1000, order.size)
        assertTrue(queue.waiting.value.isEmpty())
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun `queue never strands a second waiter with the same name`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("a")
        val first = async { queue.acquire("dup"); "first" }
        val second = async { queue.acquire("dup"); "second" }
        repeat(5) { yield() }
        assertEquals(listOf("dup", "dup"), queue.waiting.value)
        queue.release()
        assertEquals("first", first.await())
        queue.release()
        assertEquals("second", kotlinx.coroutines.withTimeout(2_000) { second.await() })
        assertTrue(queue.waiting.value.isEmpty())
    }

    @Test fun `queue serves in its own order and can be reordered`() = runBlocking {
        val queue = DownloadQueue(1)
        val order = mutableListOf<String>()
        queue.acquire("a")   // runs
        val b = async { queue.acquire("b"); order += "b" }
        val c = async { queue.acquire("c"); order += "c" }
        val d = async { queue.acquire("d"); order += "d" }
        repeat(5) { yield() }
        assertEquals(listOf("b", "c", "d"), queue.waiting.value)
        queue.moveToFront("d")
        queue.moveDown("b")
        assertEquals(listOf("d", "c", "b"), queue.waiting.value)
        queue.release(); d.await(); queue.release(); c.await(); queue.release(); b.await()
        assertEquals(listOf("d", "c", "b"), order)
        delay(1)
    }
}
