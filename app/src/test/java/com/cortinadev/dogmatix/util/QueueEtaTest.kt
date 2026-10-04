package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEtaTest {
    private val mib = 1_048_576L

    @Test fun `remaining bytes of all, speed of the running ones`() {
        val eta = QueueEta.of(listOf(
            QueueEta.Item(100 * mib, 2f, running = true),
            QueueEta.Item(100 * mib, 3f, running = true),
            QueueEta.Item(300 * mib, 0f, running = false)
        ))
        assertEquals(500 * mib, eta.remainingBytes)
        assertEquals(5 * mib, eta.bytesPerSecond)
        assertEquals(100L, eta.seconds)
    }

    @Test fun `no time while nothing transfers`() {
        val eta = QueueEta.of(listOf(QueueEta.Item(10 * mib, 0f, running = false)))
        assertEquals(10 * mib, eta.remainingBytes)
        assertNull(eta.seconds)
        assertNull(QueueEta.of(emptyList()).seconds)
    }

    @Test fun `negative leftovers do not count`() {
        assertEquals(0L, QueueEta.of(listOf(QueueEta.Item(-5, 1f, running = true))).remainingBytes)
    }

    @Test fun `hours and minutes round up`() {
        assertEquals(0L to 1L, QueueEta.hoursMinutes(1))
        assertEquals(0L to 1L, QueueEta.hoursMinutes(60))
        assertEquals(0L to 2L, QueueEta.hoursMinutes(61))
        assertEquals(1L to 30L, QueueEta.hoursMinutes(90 * 60))
        assertEquals(26L to 0L, QueueEta.hoursMinutes(26 * 3600))
    }
}
