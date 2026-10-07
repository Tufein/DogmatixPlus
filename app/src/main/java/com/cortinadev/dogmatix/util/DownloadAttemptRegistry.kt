package com.cortinadev.dogmatix.util

import kotlinx.coroutines.Job

/**
 * Owns delayed recovery for a download attempt, independently of the transfer job's lifetime.
 * A failed transfer has already ended when its retry timer fires. File name and FAILED status
 * alone cannot distinguish it from a newer attempt that the user started in the meantime.
 */
class DownloadAttemptRegistry {
    class Attempt internal constructor()
    private class Entry(val attempt: Attempt, var recovery: Job? = null)
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun begin(fileName: String): Attempt {
        entries.remove(fileName)?.recovery?.cancel()
        return Attempt().also { entries[fileName] = Entry(it) }
    }

    /** A pause, stop, removal or manual retry supersedes the old attempt's recovery. */
    @Synchronized
    fun invalidate(fileName: String) {
        entries.remove(fileName)?.recovery?.cancel()
    }

    @Synchronized
    fun isCurrent(fileName: String, attempt: Attempt): Boolean = entries[fileName]?.attempt === attempt

    /** Register before starting a LAZY recovery job, so an immediate user action cannot miss it. */
    @Synchronized
    fun registerRecovery(fileName: String, attempt: Attempt, job: Job): Boolean {
        val entry = entries[fileName]?.takeIf { it.attempt === attempt }
        if (entry == null) {
            job.cancel()
            return false
        }
        entry.recovery?.cancel()
        entry.recovery = job
        return true
    }

    /** An old timer completing must not detach a newer attempt's timer. */
    @Synchronized
    fun finishRecovery(fileName: String, attempt: Attempt, job: Job) {
        entries[fileName]?.takeIf { it.attempt === attempt && it.recovery === job }?.recovery = null
    }
}
