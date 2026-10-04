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
class DownloadQueue(slots: Int) {
    private class Ticket(val name: String) { val granted = CompletableDeferred<Unit>() }

    private val lock = Any()
    private var slots = slots.coerceAtLeast(1)
    private var running = 0
    private val tickets = ArrayList<Ticket>()

    private val _waiting = MutableStateFlow<List<String>>(emptyList())
    /** Waiting downloads, first to start first. */
    val waiting: StateFlow<List<String>> = _waiting.asStateFlow()

    fun setSlots(slots: Int) {
        synchronized(lock) { this.slots = slots.coerceAtLeast(1); grantLocked() }
        publish()
    }

    /** Suspends until [name] may start; call [release] when it is done (also when it failed). */
    suspend fun acquire(name: String) {
        val ticket = Ticket(name)
        synchronized(lock) { tickets += ticket; grantLocked() }
        publish()
        try {
            ticket.granted.await()
        } catch (e: Throwable) {
            synchronized(lock) {
                // Cancelled while waiting: leave the line. Cancelled just as the slot was granted:
                // hand the slot on, nobody will release it.
                if (!tickets.remove(ticket) && ticket.granted.isCompleted) { running = (running - 1).coerceAtLeast(0); grantLocked() }
            }
            publish()
            throw e
        }
    }

    fun release() {
        synchronized(lock) { running = (running - 1).coerceAtLeast(0); grantLocked() }
        publish()
    }

    fun moveUp(name: String) = reorder(name) { i -> if (i > 0) java.util.Collections.swap(tickets, i, i - 1) }
    fun moveDown(name: String) = reorder(name) { i -> if (i < tickets.lastIndex) java.util.Collections.swap(tickets, i, i + 1) }
    fun moveToFront(name: String) = reorder(name) { i -> tickets.add(0, tickets.removeAt(i)) }

    private fun reorder(name: String, change: (Int) -> Unit) {
        synchronized(lock) {
            val i = tickets.indexOfFirst { it.name == name }
            if (i >= 0) change(i)
        }
        publish()
    }

    /** Starts the first waiters while slots are free; call with [lock] held. */
    private fun grantLocked() {
        while (running < slots && tickets.isNotEmpty()) {
            val next = tickets.removeAt(0)
            running++
            next.granted.complete(Unit)
        }
    }

    private fun publish() { _waiting.value = synchronized(lock) { tickets.map { it.name } } }
}
