package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test fun `shared text gives the link and what it is`() {
        assertEquals(SharedLink.Kind.MAGNET, SharedLinks.parse("Look: magnet:?xt=urn:btih:abc&dn=x nice")!!.kind)
        val file = SharedLinks.parse("Mario https://example.org/roms/Super%20Mario%20(USA).zip.")!!
        assertEquals(SharedLink.Kind.FILE, file.kind)
        assertEquals("Super Mario (USA).zip", file.fileName)
        assertEquals(SharedLink.Kind.DIRECTORY, SharedLinks.parse("https://example.org/roms/snes/")!!.kind)
        assertEquals(SharedLink.Kind.TORRENT, SharedLinks.parse("https://x.org/a.torrent")!!.kind)
        assertNull(SharedLinks.parse("no link here"))
    }
}
