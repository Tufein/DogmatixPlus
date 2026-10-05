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

    @Test fun `webdav passwords and passphrases never survive`() {
        val out = DiagnosticsRedactor.redact(
            "dav_password=Tr0ub4dor and dav_passphrase: \"staple\" and \"password\":\"p4ss!w0rd\" and passphrase=horse"
        )
        assertFalse(out, out.contains("Tr0ub4dor"))
        assertFalse(out, out.contains("staple"))
        assertFalse(out, out.contains("p4ss"))
        assertFalse(out, out.contains("horse"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun `a passphrase with spaces is removed as a known secret`() {
        val out = DiagnosticsRedactor.redact("sealing with correct horse battery staple failed", listOf("correct horse battery staple"))
        assertEquals("sealing with <redacted> failed", out)
    }

    @Test fun `a basic authorization header is removed`() {
        val out = DiagnosticsRedactor.redact("Authorization: Basic c2FtOnNlY3JldA==")
        assertFalse(out, out.contains("c2FtOnNlY3JldA"))
    }

    @Test fun `a webdav server and user are removed when passed as known secrets`() {
        val out = DiagnosticsRedactor.redact(
            "PROPFIND https://cloud.example.com/remote.php/dav/files/samuel/Dogmatix/ for samuel failed",
            listOf("cloud.example.com", "samuel")
        )
        assertFalse(out, out.contains("cloud.example.com"))
        assertFalse(out, out.contains("samuel"))
        assertTrue(out, out.contains("PROPFIND"))
    }
}
