package com.cortinadev.dogmatix.util

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueOrderTest {
    @Test fun `batch priority preserves queue order and leaves running downloads alone`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("running")
        val names = listOf("a", "b", "c", "d", "e")
        val waiters = names.map { name -> launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire(name) } }
        queue.moveToFront(listOf("e", "c", "running", "missing", "c"))
        assertEquals(listOf("c", "e", "a", "b", "d"), queue.waiting.value)
        queue.release()
        waiters[2].join()
        assertEquals(listOf("e", "a", "b", "d"), queue.waiting.value)
        queue.release()
        waiters[4].join()
        assertEquals(listOf("a", "b", "d"), queue.waiting.value)
        waiters.forEach { it.cancel() }
        waiters.forEach { it.join() }
        queue.release()
    }

    @Test fun `batch priority keeps per host fairness and canceled selections release their slot`() = runBlocking {
        val queue = DownloadQueue(2, perHost = 1)
        queue.acquire("running-one", "one")
        queue.acquire("running-two", "two")
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire("blocked", "one") }
        val other = launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire("other", "three") }
        val last = launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire("last", "two") }
        queue.moveToFront(listOf("last", "blocked"))
        assertEquals(listOf("blocked", "last", "other"), queue.waiting.value)
        queue.release("two")
        last.join()
        assertEquals(listOf("blocked", "other"), queue.waiting.value)
        blocked.cancel()
        blocked.join()
        queue.release("two")
        withTimeout(2_000) { other.join() }
        assertTrue(queue.waiting.value.isEmpty())
        queue.release("three")
        queue.release("one")
    }

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
        assertEquals("second", withTimeout(2_000) { second.await() })
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

    @Test fun `a large blocked batch coalesces queue snapshots and settles in order`() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("running")
        var emissions = 0
        val watcher = launch(Dispatchers.Unconfined) { queue.waiting.collect { emissions++ } }
        val count = 2_000
        val waiters = (0 until count).map { i ->
            launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire("g$i") }
        }
        val snapshot = withTimeout(5_000) { queue.waiting.first { it.size == count } }
        assertEquals((0 until count).map { "g$it" }, snapshot)
        assertTrue("Bulk enqueue emitted $emissions complete snapshots", emissions < count / 2)
        // A user reorder remains immediate even while large queue snapshots are batched.
        queue.moveToFront("g1999")
        assertEquals("g1999", queue.waiting.value.first())
        waiters.forEach { it.cancel() }
        waiters.forEach { it.join() }
        assertTrue(queue.waiting.value.isEmpty())
        queue.release()
        watcher.cancel()
    }

    @Test fun `canceling a waiter does not consume a slot or a host allowance`() = runBlocking {
        val queue = DownloadQueue(1, perHost = 1)
        queue.acquire("running", "server")
        val canceled = launch(start = CoroutineStart.UNDISPATCHED) { queue.acquire("canceled", "server") }
        canceled.cancel()
        canceled.join()
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.acquire("next", "server"); "next" }
        assertEquals(listOf("next"), queue.waiting.value)
        queue.release("server")
        assertEquals("next", withTimeout(2_000) { next.await() })
        queue.release("server")
        assertTrue(queue.waiting.value.isEmpty())
    }

    @Test fun `a busy server does not block other hosts`() = runBlocking {
        val queue = DownloadQueue(2, perHost = 1)
        queue.acquire("first", "one")
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { queue.acquire("second", "one"); "second" }
        val other = async(start = CoroutineStart.UNDISPATCHED) { queue.acquire("other", "two"); "other" }
        assertEquals("other", withTimeout(2_000) { other.await() })
        assertEquals(listOf("second"), queue.waiting.value)
        queue.release("one")
        assertEquals("second", withTimeout(2_000) { blocked.await() })
        queue.release("two")
        queue.release("one")
    }
}
