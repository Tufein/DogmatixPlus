package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AutoBackupTest {
    @Test fun `automatic backups rotate and know when they are due`() {
        val names = (1..7).map { BackupRotation.fileName(LocalDate.of(2026, 9, it)) } + "dogmatix-backup-2026-01-01.json"
        assertEquals(listOf(BackupRotation.fileName(LocalDate.of(2026, 9, 2)), BackupRotation.fileName(LocalDate.of(2026, 9, 1))), BackupRotation.toDelete(names))
        val day = 24L * 3_600_000
        assertTrue(BackupRotation.isDue(0, 10 * day, 7))
        assertFalse(BackupRotation.isDue(5 * day, 10 * day, 7))
        assertTrue(BackupRotation.isDue(3 * day, 10 * day, 7))
    }
}
