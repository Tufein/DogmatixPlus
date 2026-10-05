package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceHealthTest {

    @Test fun `no enabled source`() {
        assertEquals(SourceHealth.State.NO_SOURCES, SourceHealth.of(emptyList()).state)
    }

    @Test fun `nothing scanned yet`() {
        assertEquals(SourceHealth.State.NOT_SCANNED, SourceHealth.of(listOf(null, null)).state)
    }

    @Test fun `all scanned sources answered, one still unscanned`() {
        val h = SourceHealth.of(listOf(true, null, true))
        assertEquals(SourceHealth.State.OK, h.state)
        assertEquals(2, h.scanned)
        assertEquals(0, h.failed)
    }

    @Test fun `some failed`() {
        val h = SourceHealth.of(listOf(true, false, false))
        assertEquals(SourceHealth.State.PARTIAL, h.state)
        assertEquals(2, h.failed)
    }

    @Test fun `every scanned source failed`() {
        val h = SourceHealth.of(listOf(false, null))
        assertEquals(SourceHealth.State.FAILED, h.state)
        assertEquals(1, h.failed)
        assertEquals(1, h.scanned)
    }

    @Test fun `source kinds`() {
        assertEquals(SourceKind.ROMM, SourceKind.of("romm://psp"))
        assertEquals(SourceKind.TORRENT, SourceKind.of("magnet:?xt=urn:btih:abc"))
        assertEquals(SourceKind.TORRENT, SourceKind.of("https://example.org/set.torrent?dl=1"))
        assertEquals(SourceKind.TORRENT, SourceKind.of("/data/user/0/app/files/torrents/uploaded_1.torrent"))
        assertEquals(SourceKind.TORRENT, SourceKind.of("content://downloads/42"))
        assertEquals(SourceKind.WEB, SourceKind.of(" https://example.org/roms/snes/ "))
        assertEquals(SourceKind.WEB, SourceKind.of("HTTP://example.org/"))
        assertEquals(SourceKind.OTHER, SourceKind.of("ftp://example.org/"))
    }
}
