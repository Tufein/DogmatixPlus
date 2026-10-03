package com.cortinadev.dogmatix.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Traffic towards one server during a scan. Normally [parallel] requests may run at once, each
 * after a short [pauseMs]. Once the server pushes back (rate limit, overload) the gate slows down
 * for the rest of the scan: one request at a time, a longer pause, and nothing at all until the
 * moment the server named.
 */
class HostGate(parallel: Int, private val pauseMs: Long, private val slowPauseMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val permits = Semaphore(parallel)
    private val serial = Mutex()
    @Volatile var slowedDown: Boolean = false
        private set
    @Volatile private var notBefore: Long = 0L

    suspend fun <T> run(block: suspend () -> T): T = permits.withPermit {
        if (slowedDown) serial.withLock { waitTurn(); block() } else { waitTurn(); block() }
    }

    private suspend fun waitTurn() {
        val wait = notBefore - clock()
        if (wait > 0) delay(wait)
        delay(if (slowedDown) slowPauseMs else pauseMs)
    }

    /** The server pushed back: slow down, and hold off for [waitMs]. */
    fun pushBack(waitMs: Long) {
        slowedDown = true
        notBefore = maxOf(notBefore, clock() + waitMs)
    }
}
