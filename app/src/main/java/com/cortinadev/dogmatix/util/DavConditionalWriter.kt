package com.cortinadev.dogmatix.util

/**
 * The careful write of ONE shared file ([DeviceSyncEngine] and [SharedWishlistEngine] use it): a
 * write that only lands when the file is still the version the caller merged. Used inside the
 * caller's "read, merge, write" loop: when [attempt] returns false the caller reads and merges
 * again; true means the bytes are on the server and were seen there.
 *
 * - With a strong ETag the PUT is conditional (`If-Match`; `If-None-Match: *` for a new file).
 * - A server that gave no ETag, or ignores the conditions, would turn that into a blind overwrite.
 *   So when no condition can be enforced the file is read once more right before the write (changed
 *   = merge again) and read back after it: a write that is not what the server now holds was
 *   overwritten by another device, so the caller merges again. A new file is read back too.
 * - A server that refuses its own ETags (some proxies) gets one unconditional write, but only
 *   after the same read-before and read-back checks, never blindly.
 *
 * The window between the last read and the write cannot be closed without server support; the
 * checks make it milliseconds. Bounded by [MAX_ATTEMPTS]: then it fails with a precondition error.
 * Pure JVM for the tests.
 */
class DavConditionalWriter(
    private val store: DavStore,
    private val url: String,
    private val contentType: String,
    private val maxBytes: Long
) {
    private var attempts = 0
    private var rejected = 0
    private var firstRejected: Pair<Boolean, String?>? = null
    private var unconditional = false

    /**
     * Tries to write [bytes] over [file] (what the caller read; null = no file). [ours] tells whether
     * bytes read back from the server are this write (equal, or carrying its revision mark).
     * Returns false when the caller must read and merge again; throws on any other failure.
     */
    fun attempt(file: DavFile?, bytes: ByteArray, ours: (ByteArray) -> Boolean): Boolean {
        if (++attempts > MAX_ATTEMPTS) throw DavException(DavProblem.PRECONDITION, 412, "the file keeps changing")
        val blind = unconditional || (file != null && file.etag == null)
        if (blind) {
            val again = store.get(url, maxBytes)
            if (!same(again, file)) return false
        }
        try {
            store.put(url, bytes, contentType, ifMatch = if (blind) null else file?.etag, ifNoneMatch = !blind && file == null)
        } catch (e: DavException) {
            if (e.problem != DavProblem.PRECONDITION) throw e
            rejected++
            val seen = (file != null) to file?.etag
            when {
                // The same state refused twice: nobody wrote, the server's conditions are broken.
                rejected >= 2 && seen == firstRejected && !unconditional -> unconditional = true
                else -> firstRejected = seen
            }
            return false
        }
        if (blind || file?.etag == null) {
            val after = store.get(url, maxBytes) ?: return false
            if (!ours(after.bytes)) return false
        }
        return true
    }

    private fun same(a: DavFile?, b: DavFile?): Boolean =
        (a == null && b == null) || (a != null && b != null && a.bytes.contentEquals(b.bytes))

    companion object {
        const val MAX_ATTEMPTS = 6
    }
}
