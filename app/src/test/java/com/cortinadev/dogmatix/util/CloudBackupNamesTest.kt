package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudBackupNamesTest {

    private val hour = 3_600_000L

    @Test fun `names carry the UTC time and the device`() {
        assertEquals(
            "dogmatix-20261004-2114-retroid-pocket-5.dgxb",
            CloudBackupNames.fileName(Instant.parse("2026-10-04T21:14:59Z"), "Retroid Pocket 5")
        )
    }

    @Test fun `device names become short slugs`() {
        assertEquals("ayn-odin-2-portal", CloudBackupNames.deviceSlug("  AYN Odin 2 Portal ✨ "))
        assertEquals("senor-s-pocket", CloudBackupNames.deviceSlug("Señor's Pocket"))
        assertEquals("device", CloudBackupNames.deviceSlug("✨✨"))
        assertEquals("device", CloudBackupNames.deviceSlug(""))
        val long = CloudBackupNames.deviceSlug("a".repeat(31) + " b c d")
        assertTrue(long.length <= 32)
        assertFalse(long.endsWith("-"))
    }

    @Test fun `parses its own names only`() {
        assertEquals(1791148440000L to "retroid-pocket-5", CloudBackupNames.parse("dogmatix-20261004-2114-retroid-pocket-5.dgxb"))
        assertNull(CloudBackupNames.parse("dogmatix-auto-backup-2026-10-04.json"))
        assertNull(CloudBackupNames.parse("dogmatix-20261304-2114-x.dgxb"))     // month 13
        assertNull(CloudBackupNames.parse("holiday.dgxb"))
    }

    @Test fun `rotation keeps the newest of this device and never touches others`() {
        val mine = (1..9).map { "dogmatix-2026100${it}-0300-thor.dgxb" }
        val other = (1..9).map { "dogmatix-2026100${it}-0300-odin.dgxb" }
        val foreign = listOf("notes.txt", "dogmatix-old.dgxb")
        val delete = CloudBackupNames.toDelete(mine + other + foreign, "Thor", keep = 7)
        assertEquals(listOf("dogmatix-20261002-0300-thor.dgxb", "dogmatix-20261001-0300-thor.dgxb"), delete)
        assertTrue(CloudBackupNames.toDelete(mine, "Thor", keep = 30).isEmpty())
        // At least one backup always stays.
        assertEquals(8, CloudBackupNames.toDelete(mine, "Thor", keep = 0).size)
    }

    @Test fun `the listing shows backups newest first, with device and size`() {
        val folder = "https://h/dav/Dogmatix/backups/"
        val listed = CloudBackupNames.listing(
            listOf(
                Triple("dogmatix-20261001-0300-thor.dgxb", 4000L, null),
                Triple("dogmatix-20261003-0300-odin.dgxb", 5000L, null),
                Triple("hand made.dgxb", null, 1791148442000L),
                Triple("readme.txt", 10L, null),
                Triple(".dogmatix-test.dgxb", 1L, null)
            ),
            folder, "Thor"
        )
        assertEquals(listOf("hand made.dgxb", "dogmatix-20261003-0300-odin.dgxb", "dogmatix-20261001-0300-thor.dgxb"), listed.map { it.name })
        assertEquals("https://h/dav/Dogmatix/backups/hand%20made.dgxb", listed[0].url)
        assertEquals("", listed[0].device)
        assertEquals("odin", listed[1].device)
        assertFalse(listed[1].isThisDevice)
        assertTrue(listed[2].isThisDevice)
        assertEquals(4000L, listed[2].size)
    }

    @Test fun `a backup is due once a day`() {
        val now = 1_800_000_000_000L
        assertTrue(CloudBackupNames.isDue(0, now))
        assertFalse(CloudBackupNames.isDue(now - 2 * hour, now))
        assertTrue(CloudBackupNames.isDue(now - 23 * hour, now))
        assertTrue(CloudBackupNames.isDue(now + 5 * hour, now))   // clock went back
    }

    @Test fun `keep steps through its choices`() {
        assertEquals(10, CloudBackupNames.shiftKeep(7, 1))
        assertEquals(5, CloudBackupNames.shiftKeep(7, -1))
        assertEquals(30, CloudBackupNames.shiftKeep(30, 1))
        assertEquals(3, CloudBackupNames.shiftKeep(3, -1))
        assertEquals(10, CloudBackupNames.shiftKeep(8, 1))
    }
}
