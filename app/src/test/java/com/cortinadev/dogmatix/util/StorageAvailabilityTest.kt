package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class StorageAvailabilityTest {
    @Test fun reconnectRequiresAnExplicitResumeAndReadOnlyCannotWrite() {
        assertEquals(StorageAccessStatus.RETURNED, StorageAvailability.transition(StorageAccessStatus.MISSING, StorageAccessStatus.AVAILABLE))
        assertEquals(StorageAccessStatus.RETURNED, StorageAvailability.transition(StorageAccessStatus.ACCESS_LOST, StorageAccessStatus.AVAILABLE))
        assertEquals(StorageAccessStatus.RETURNED, StorageAvailability.transition(StorageAccessStatus.READ_ONLY, StorageAccessStatus.AVAILABLE))
        assertTrue(StorageAvailability.readable(StorageAccessStatus.READ_ONLY))
        assertFalse(StorageAvailability.writable(StorageAccessStatus.READ_ONLY))
        assertFalse(StorageAvailability.readable(StorageAccessStatus.UNAVAILABLE))
    }

    @Test fun siblingFoldersAreDifferentStorageScopes() {
        assertTrue(StorageAvailability.contains("/sd/Games", "/sd/Games/gba"))
        assertFalse(StorageAvailability.contains("/sd/Games", "/sd/Games-old/gba"))
        assertFalse(StorageAvailability.contains("/sd/Games/gba", "/sd/Games"))
    }
}
