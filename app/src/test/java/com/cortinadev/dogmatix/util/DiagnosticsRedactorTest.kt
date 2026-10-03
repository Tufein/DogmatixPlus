package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test fun `tokens never survive`() {
        val out = DiagnosticsRedactor.redact("Authorization: Bearer rmm_abcDEF123456 and token=hunter2 and password: \"s3cret\"")
        assertFalse(out.contains("abcDEF123456"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("s3cret"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun `server addresses and magnets are removed`() {
        val out = DiagnosticsRedactor.redact("GET https://romm.home.lan:8443/api/roms?x=1 failed; magnet:?xt=urn:btih:ABC&dn=Game; host 192.168.1.20:8080; mail me@example.com")
        assertFalse(out.contains("home.lan"))
        assertFalse(out.contains("btih"))
        assertFalse(out.contains("192.168"))
        assertFalse(out.contains("me@example.com"))
        assertTrue(out.contains("https://<host>"))
    }

    @Test fun `known secrets are removed wherever they appear`() {
        val out = DiagnosticsRedactor.redact("connect to romm.home.lan with abcd1234efgh", listOf("romm.home.lan", "abcd1234efgh", "x"))
        assertEquals("connect to <redacted> with <redacted>", out)
    }

    @Test fun `ordinary log text is left alone`() {
        val line = "I/DownloadService: Resuming Game.zip from 1048576 bytes"
        assertEquals(line, DiagnosticsRedactor.redact(line))
    }

    @Test fun `content uris are hidden`() {
        assertEquals("opened content://<uri>", DiagnosticsRedactor.redact("opened content://com.android.externalstorage.documents/tree/primary%3AROMs"))
    }
}
