package com.cortinadev.dogmatix.util

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The download slots: at most [slots] downloads run at once, and the ones waiting go in the
 * order of [waiting], which the user can change (move up / down / to the front). A plain
 * semaphore would always serve the oldest first.
 *
 * Each waiter holds its own ticket and is woken only when it is its turn: with hundreds of
 * downloads queued, waking every waiter on every change (as 2.5.0 did) kept all cores busy and
 * starved the UI thread into "app not responding".
 */
class DownloadQueue(slots: Int, perHost: Int = 0) {
    private class Ticket(val name: String, val host: String) { val granted = CompletableDeferred<Unit>() }

    private val lock = Any()
    private var slots = slots.coerceAtLeast(1)
    /** At most this many at once from one server; 0 = no limit. */
    private var perHost = perHost.coerceAtLeast(0)
    private var running = 0
    private val runningPerHost = HashMap<String, Int>()
    private val tickets = ArrayList<Ticket>()
    private val publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var publishScheduled = false

    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    /** Waiting downloads, first to start first. */
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    fun setSlots(slots: Int) {
        synchronized(lock) { this.slots = slots.coerceAtLeast(1); grantLocked(); publishLocked() }
    }

    /** Limit per server (host name); 0 lifts it. A waiting download of a busy server lets later ones of other servers go first. */
    fun setPerHost(limit: Int) {
        synchronized(lock) { perHost = limit.coerceAtLeast(0); grantLocked(); publishLocked() }
    }

    /**
     * Suspends until [name] may start; call [release] with the same [host] when it is done (also
     * when it failed). [host] is the server it comes from; empty = not limited per server.
     */
    suspend fun acquire(name: String, host: String = "") {
        val ticket = Ticket(name, host.lowercase())
        synchronized(lock) { tickets += ticket; grantLocked(); publishLocked() }
        try {
            ticket.granted.await()
        } catch (e: Throwable) {
            synchronized(lock) {
                // Cancelled while waiting: leave the line. Cancelled just as the slot was granted:
                // hand the slot on, nobody will release it.
                if (!tickets.remove(ticket) && ticket.granted.isCompleted) { releaseLocked(ticket.host); grantLocked() }
                publishLocked()
            }
            throw e
        }
    }

    fun release(host: String = "") {
        synchronized(lock) { releaseLocked(host.lowercase()); grantLocked(); publishLocked() }
    }

    private fun releaseLocked(host: String) {
        running = (running - 1).coerceAtLeast(0)
        if (host.isNotEmpty()) runningPerHost[host]?.let { if (it <= 1) runningPerHost.remove(host) else runningPerHost[host] = it - 1 }
    }

    fun moveUp(name: String) = reorder(name) { i -> if (i > 0) Collections.swap(tickets, i, i - 1) }
    fun moveDown(name: String) = reorder(name) { i -> if (i < tickets.lastIndex) Collections.swap(tickets, i, i + 1) }
    fun moveToFront(name: String) = reorder(name) { i -> tickets.add(0, tickets.removeAt(i)) }

    private fun reorder(name: String, change: (Int) -> Unit) {
        synchronized(lock) {
            val i = tickets.indexOfFirst { it.name == name }
            if (i >= 0) { change(i); grantLocked() }
            // Reorders made by the user should be visible immediately, also in a large queue.
            publishLocked(immediate = true)
        }
    }

    private fun hostFull(host: String) = perHost > 0 && host.isNotEmpty() && (runningPerHost[host] ?: 0) >= perHost

    /** Starts waiters in order while slots are free, skipping ones whose server is at its limit; call with [lock] held. */
    private fun grantLocked() {
        while (running < slots) {
            val i = tickets.indexOfFirst { !hostFull(it.host) }
            if (i < 0) return
            val next = tickets.removeAt(i)
            running++
            if (next.host.isNotEmpty()) runningPerHost[next.host] = (runningPerHost[next.host] ?: 0) + 1
            next.granted.complete(Unit)
        }
    }

    /**
     * Copy a small queue immediately. A bulk start used to copy and emit the growing list once
     * per waiter (quadratic allocation), even though only a few downloads could run. Large bursts
     * share one short delayed publication. The snapshot and its publication hold the same lock:
     * another producer cannot publish an older queue after a newer one.
     */
    private fun publishLocked(immediate: Boolean = false) {
        if (immediate || tickets.size < BATCH_THRESHOLD) {
            _waiting.value = tickets.map { it.name }
        } else if (!publishScheduled) {
            publishScheduled = true
            publisherScope.launch {
                delay(PUBLISH_BATCH_MS)
                synchronized(lock) {
                    publishScheduled = false
                    _waiting.value = tickets.map { it.name }
                }
            }
        }
    }

    private companion object {
        const val BATCH_THRESHOLD = 64
        const val PUBLISH_BATCH_MS = 25L
    }
}
