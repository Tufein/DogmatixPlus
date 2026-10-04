package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUrlsTest {
    @Test fun `mirror urls swap the base address`() {
        assertEquals(listOf("https://b.org/gba/Game%20(USA).zip", "https://c.org/x/gba/Game%20(USA).zip"),
            MirrorUrls.alternatives("https://a.org/gba/Game%20(USA).zip", "https://a.org/gba/", listOf("https://b.org/gba", "https://c.org/x/gba/")))
        assertEquals(listOf("https://a.org/gba/G.zip"), MirrorUrls.alternatives("https://b.org/gba/G.zip", "https://a.org/gba/", listOf("https://b.org/gba/")))
        assertTrue(MirrorUrls.alternatives("https://z.org/G.zip", "https://a.org/gba/", listOf("https://b.org/gba/")).isEmpty())
    }
}
