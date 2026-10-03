package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrAndUpdateTest {
    @Test fun `a long list is cut into parts that come back together in any order`() {
        val text = (1..400).joinToString("\n") { "https://example.org/roms/console-$it/ ${"x".repeat(it % 17)} ${it * 7919}" }
        val parts = QrTransfer.encode(text, id = "abc123")
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.startsWith("DGXS1:") && it.length <= QrTransfer.CHUNK + 30 })
        val collector = QrTransfer.Collector()
        parts.reversed().forEach { assertTrue(collector.add(it)) }
        assertTrue(collector.complete)
        assertEquals(text, collector.text())
    }

    @Test fun `foreign codes are refused and a new list starts over`() {
        val collector = QrTransfer.Collector()
        assertFalse(collector.add("https://example.org"))
        val a = QrTransfer.encode("a".repeat(5000) + (1..3000).joinToString(), id = "aaaaaa")
        val b = QrTransfer.encode("b", id = "bbbbbb")
        collector.add(a.first())
        assertFalse(collector.complete)
        collector.add(b.single())
        assertTrue(collector.complete)
        assertEquals("b", collector.text())
        assertNull(QrTransfer.parsePart("DGXS1:3/2:x:data"))
    }

    @Test fun `the apk checksum is read from SHA256SUMS`() {
        val sums = """
            c144831cd977813e46f05f3bcd7d60e8d4a8d0f42616acfc742dc1d7846b978a  DogmatixPlus-release.apk
            0000000000000000000000000000000000000000000000000000000000000000 *DogmatixPlus-debug.apk
        """.trimIndent()
        assertEquals("c144831cd977813e46f05f3bcd7d60e8d4a8d0f42616acfc742dc1d7846b978a", UpdateAssets.checksumFor(sums, "DogmatixPlus-release.apk"))
        assertEquals("0".repeat(64), UpdateAssets.checksumFor(sums, UpdateAssets.apkName(debugBuild = true)))
        assertNull(UpdateAssets.checksumFor(sums, "other.apk"))
    }
}
