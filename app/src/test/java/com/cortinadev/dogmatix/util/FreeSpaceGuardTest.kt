package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpaceGuardTest {
    @Test fun `downloads wait below the free-space limit`() {
        val gb = 1_073_741_824L
        val conditions = DownloadRules(minFreeBytes = 5 * gb)
        assertEquals(listOf(WaitReason.STORAGE), DownloadPolicy.waitingFor(conditions, DeviceConditions(true, true, 0, freeBytes = 2 * gb)))
        assertTrue(DownloadPolicy.waitingFor(conditions, DeviceConditions(true, true, 0, freeBytes = 9 * gb)).isEmpty())
        assertTrue(DownloadPolicy.waitingFor(conditions, DeviceConditions(true, true, 0, freeBytes = null)).isEmpty())
        assertTrue(DownloadPolicy.waitingFor(DownloadRules(), DeviceConditions(true, true, 0, freeBytes = 1)).isEmpty())
    }
}
