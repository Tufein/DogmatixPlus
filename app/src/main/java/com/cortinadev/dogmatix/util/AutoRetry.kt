package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.HttpStatusException
import java.io.IOException

/**
 * Failed downloads that may succeed later (the network dropped, the server was busy) are started
 * again by themselves a few times, with a longer wait each time. Pure JVM for the tests.
 */
object AutoRetry {
    /** Wait before the first, second and third automatic retry; there are no more after that. */
    val WAITS_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)

    /** How long to wait before retry number [retriesSoFar] + 1, or null when the limit is reached. */
    fun waitBeforeRetry(retriesSoFar: Int): Long? = WAITS_MS.getOrNull(retriesSoFar)

    /** Timeouts, rate limits and server errors may pass; "not found" or "forbidden" will not. */
    fun temporaryStatus(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code in 500..599

    /** A dropped or refused connection or a server that may recover; anything else is left to the user. */
    fun isTemporary(error: Throwable): Boolean = when (error) {
        is HttpStatusException -> temporaryStatus(error.code)
        is IOException -> true
        else -> false
    }
}
