package com.cortinadev.dogmatix.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    /** Waiting downloads, first to start first. */
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    fun setSlots(slots: Int) {
        synchronized(lock) { this.slots = slots.coerceAtLeast(1); grantLocked() }
        publish()
    }

    /** Limit per server (host name); 0 lifts it. A waiting download of a busy server lets later ones of other servers go first. */
    fun setPerHost(limit: Int) {
        synchronized(lock) { perHost = limit.coerceAtLeast(0); grantLocked() }
        publish()
    }

    /**
     * Suspends until [name] may start; call [release] with the same [host] when it is done (also
     * when it failed). [host] is the server it comes from; empty = not limited per server.
     */
    suspend fun acquire(name: String, host: String = "") {
        val ticket = Ticket(name, host.lowercase())
        synchronized(lock) { tickets += ticket; grantLocked() }
        publish()
        try {
            ticket.granted.await()
        } catch (e: Throwable) {
            synchronized(lock) {
                // Cancelled while waiting: leave the line. Cancelled just as the slot was granted:
                // hand the slot on, nobody will release it.
                if (!tickets.remove(ticket) && ticket.granted.isCompleted) { releaseLocked(ticket.host); grantLocked() }
            }
            publish()
            throw e
        }
    }

    fun release(host: String = "") {
        synchronized(lock) { releaseLocked(host.lowercase()); grantLocked() }
        publish()
    }

    private fun releaseLocked(host: String) {
        running = (running - 1).coerceAtLeast(0)
        if (host.isNotEmpty()) runningPerHost[host]?.let { if (it <= 1) runningPerHost.remove(host) else runningPerHost[host] = it - 1 }
    }

    fun moveUp(name: String) = reorder(name) { i -> if (i > 0) java.util.Collections.swap(tickets, i, i - 1) }
    fun moveDown(name: String) = reorder(name) { i -> if (i < tickets.lastIndex) java.util.Collections.swap(tickets, i, i + 1) }
    fun moveToFront(name: String) = reorder(name) { i -> tickets.add(0, tickets.removeAt(i)) }

    private fun reorder(name: String, change: (Int) -> Unit) {
        synchronized(lock) {
            val i = tickets.indexOfFirst { it.name == name }
            if (i >= 0) { change(i); grantLocked() }
        }
        publish()
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

    private fun publish() { _waiting.value = synchronized(lock) { tickets.map { it.name } } }
}
