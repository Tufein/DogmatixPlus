package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageInsightsTest {
    private fun entry(name: String, size: Long, console: String? = "psx", scope: String = console ?: "folder:misc") =
        GameEntry(scope, console, "/ROMs/$scope", name, listOf(diskFile("$name.bin", size = size, consoleId = console, scope = scope)))

    @Test fun `space per console is biggest first and unknown folders stay apart`() {
        val usage = StorageInsights.usageByConsole(listOf(entry("a", 100), entry("b", 300), entry("c", 50, "gba"), entry("d", 400, null, "folder:misc")))
        assertEquals(listOf("psx", null, "gba"), usage.map { it.consoleId })
        assertEquals(400L, usage[0].bytes)
        assertEquals(2, usage[0].games)
    }

    @Test fun `the biggest games`() {
        assertEquals(listOf("b", "a"), StorageInsights.biggest(listOf(entry("a", 100), entry("b", 300), entry("c", 5)), 2).map { it.baseName })
    }

    @Test fun `what the queue needs and whether it fits`() {
        val need = StorageInsights.queueNeed(listOf(StorageInsights.QueueItem(1_000, false), StorageInsights.QueueItem(4_000, true), StorageInsights.QueueItem(-5, false)))
        assertEquals(5_000L, need.downloadBytes)
        assertEquals(4_000L, need.unpackBytes)
        assertEquals(9_000L, need.total)
        assertEquals(0L, StorageInsights.shortfall(need, 1_000_000, margin = 0))
        assertEquals(1_000L, StorageInsights.shortfall(need, 8_000, margin = 0))
        assertEquals(1_100L, StorageInsights.shortfall(need, 8_000, margin = 100))
        assertNull(StorageInsights.shortfall(need, null))
    }

    @Test fun `archives need room to unpack`() {
        assertTrue(StorageInsights.isExtractable(".ZIP"))
        assertTrue(StorageInsights.isExtractable("7z"))
        assertTrue(!StorageInsights.isExtractable("chd"))
    }
}
