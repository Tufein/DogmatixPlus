package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStatusModelTest {

    @Test fun `nothing set up hides the icon`() {
        assertEquals(CloudStatus.Hidden, CloudStatusModel.merge(emptyList()))
        val status = CloudStatusModel.merge(listOf(CloudStatusPart(configured = false, syncing = true, attention = 3)))
        assertEquals(CloudActivity.HIDDEN, status.activity)
        assertFalse(status.visible)
    }

    @Test fun `set up and quiet is idle`() {
        val status = CloudStatusModel.merge(listOf(CloudStatusPart(configured = true), CloudStatusPart(configured = false)))
        assertEquals(CloudStatus(CloudActivity.IDLE), status)
        assertTrue(status.visible)
    }

    @Test fun `syncing wins over attention and keeps the count`() {
        val status = CloudStatusModel.merge(
            listOf(
                CloudStatusPart(configured = true, syncing = true, progress = 1.5f),
                CloudStatusPart(configured = true, attention = 2),
                CloudStatusPart(configured = false, attention = 7)
            )
        )
        assertEquals(CloudActivity.SYNCING, status.activity)
        assertEquals(2, status.attention)
        assertEquals(1f, status.progress!!, 0f)
    }

    @Test fun `several syncs at once show no progress`() {
        val status = CloudStatusModel.merge(
            listOf(
                CloudStatusPart(configured = true, syncing = true, progress = 0.2f),
                CloudStatusPart(configured = true, syncing = true, progress = 0.9f)
            )
        )
        assertEquals(CloudActivity.SYNCING, status.activity)
        assertNull(status.progress)
    }

    @Test fun `waiting work shows attention with the total`() {
        val status = CloudStatusModel.merge(
            listOf(
                CloudStatusPart(configured = true, attention = 2, progress = 0.5f),
                CloudStatusPart(configured = true, attention = -4),
                CloudStatusPart(configured = true, attention = 1)
            )
        )
        assertEquals(CloudStatus(CloudActivity.ATTENTION, attention = 3), status)
    }

    @Test fun `the save sync part counts what needs the user`() {
        val part = CloudStatusModel.saveSyncPart(
            configured = true, running = false, conflicts = 2, failed = 1, hasError = true, deletionsHeld = 5
        )
        assertEquals(CloudStatusPart(configured = true, syncing = false, attention = 5), part)
        val running = CloudStatusModel.saveSyncPart(true, running = true, conflicts = 0, failed = 0, hasError = false, deletionsHeld = 0, progress = "3 / 12")
        assertTrue(running.syncing)
        assertEquals(0.25f, running.progress!!, 0.0001f)
        val off = CloudStatusModel.saveSyncPart(false, running = true, conflicts = 4, failed = 0, hasError = true, deletionsHeld = 0)
        assertFalse(off.syncing)
        assertEquals(0, off.attention)
    }

    @Test fun `progress text is read only when it is a count`() {
        assertEquals(0.25f, CloudStatusModel.parseProgress(" 3/12 ")!!, 0.0001f)
        assertEquals(1f, CloudStatusModel.parseProgress("14 / 12")!!, 0f)
        assertNull(CloudStatusModel.parseProgress("3 / 0"))
        assertNull(CloudStatusModel.parseProgress("Uploading Game.srm"))
        assertNull(CloudStatusModel.parseProgress(""))
        assertNull(CloudStatusModel.parseProgress(null))
    }
}
