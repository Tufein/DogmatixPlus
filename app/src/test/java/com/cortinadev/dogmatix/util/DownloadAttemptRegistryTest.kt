package com.cortinadev.dogmatix.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAttemptRegistryTest {
    @Test fun `manual restart cancels the old timer even when its transfer already ended`() = runBlocking {
        val attempts = DownloadAttemptRegistry()
        val old = attempts.begin("game.zip")
        val waiting = CompletableDeferred<Unit>()
        val timer = launch(start = CoroutineStart.LAZY) {
            waiting.complete(Unit)
            awaitCancellation()
        }
        assertTrue(attempts.registerRecovery("game.zip", old, timer))
        timer.start()
        withTimeout(2_000) { waiting.await() }

        val replacement = attempts.begin("game.zip")
        withTimeout(2_000) { timer.join() }
        assertTrue(timer.isCancelled)
        assertFalse(attempts.isCurrent("game.zip", old))
        assertTrue(attempts.isCurrent("game.zip", replacement))
    }

    @Test fun `stop prevents recovery registration racing a user action`() {
        val attempts = DownloadAttemptRegistry()
        val attempt = attempts.begin("game.zip")
        attempts.invalidate("game.zip")
        val lateTimer = Job()
        assertFalse(attempts.registerRecovery("game.zip", attempt, lateTimer))
        assertTrue(lateTimer.isCancelled)
        assertFalse(attempts.isCurrent("game.zip", attempt))
    }

    @Test fun `completion from an older attempt cannot detach the replacement timer`() {
        val attempts = DownloadAttemptRegistry()
        val old = attempts.begin("game.zip")
        val oldTimer = Job()
        assertTrue(attempts.registerRecovery("game.zip", old, oldTimer))
        val replacement = attempts.begin("game.zip")
        val replacementTimer = Job()
        assertTrue(attempts.registerRecovery("game.zip", replacement, replacementTimer))

        attempts.finishRecovery("game.zip", old, oldTimer)
        attempts.invalidate("game.zip")
        assertTrue(oldTimer.isCancelled)
        assertTrue(replacementTimer.isCancelled)
    }

    @Test fun `a later recovery replaces only its own timer and preserves other downloads`() {
        val attempts = DownloadAttemptRegistry()
        val first = attempts.begin("first.zip")
        val second = attempts.begin("second.zip")
        val firstTimer = Job()
        val secondTimer = Job()
        val replacementTimer = Job()
        assertTrue(attempts.registerRecovery("first.zip", first, firstTimer))
        assertTrue(attempts.registerRecovery("second.zip", second, secondTimer))
        assertTrue(attempts.registerRecovery("first.zip", first, replacementTimer))
        assertTrue(firstTimer.isCancelled)
        assertFalse(secondTimer.isCancelled)

        attempts.finishRecovery("first.zip", first, firstTimer)
        attempts.invalidate("first.zip")
        assertTrue(replacementTimer.isCancelled)
        assertFalse(secondTimer.isCancelled)
        attempts.invalidate("second.zip")
    }

    @Test fun `a canceled lazy timer cannot run after an immediate stop`() = runBlocking {
        val attempts = DownloadAttemptRegistry()
        val attempt = attempts.begin("game.zip")
        var ran = false
        val timer = launch(start = CoroutineStart.LAZY) { ran = true }
        assertTrue(attempts.registerRecovery("game.zip", attempt, timer))
        attempts.invalidate("game.zip")
        timer.start()
        withTimeout(2_000) { timer.join() }
        assertFalse(ran)
    }
}
