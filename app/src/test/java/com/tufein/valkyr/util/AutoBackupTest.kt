package com.tufein.valkyr.util

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

    @Test fun `4_0 writes valkyr names and still rotates the dogmatix ones by date`() {
        assertEquals("valkyr-auto-backup-2026-10-04.json", BackupRotation.fileName(LocalDate.of(2026, 10, 4)))
        val legacy = (1..4).map { "dogmatix-auto-backup-2026-09-0$it.json" }
        val current = (5..7).map { BackupRotation.fileName(LocalDate.of(2026, 9, it)) }
        val names = legacy + current + "valkyr-backup-2026-01-01.json" + "notes.json"
        assertTrue(BackupRotation.isAutoBackup("dogmatix-auto-backup-2026-09-01.json"))
        assertFalse(BackupRotation.isAutoBackup("valkyr-auto-backup-2026-09-01.txt"))
        assertEquals(listOf("dogmatix-auto-backup-2026-09-02.json", "dogmatix-auto-backup-2026-09-01.json"), BackupRotation.toDelete(names))
        // On the day of the update both names can exist for one date: the new one is kept first.
        val sameDay = listOf("dogmatix-auto-backup-2026-10-04.json", "valkyr-auto-backup-2026-10-04.json")
        assertEquals(listOf("dogmatix-auto-backup-2026-10-04.json"), BackupRotation.toDelete(sameDay, keep = 1))
    }
}
