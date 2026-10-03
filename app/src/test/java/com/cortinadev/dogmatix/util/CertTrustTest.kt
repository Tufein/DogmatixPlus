package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertTrustTest {
    private val hex = "e0e4eaeaf92e4d7a68ef620b021598f0bcbb9f9399e2bdcf18c8297077bedc46"

    @Test fun `fingerprints compare regardless of notation`() {
        val colons = "E0:E4:EA:EA:F9:2E:4D:7A:68:EF:62:0B:02:15:98:F0:BC:BB:9F:93:99:E2:BD:CF:18:C8:29:70:77:BE:DC:46"
        assertTrue(CertTrust.matches(hex, colons))
        assertTrue(CertTrust.matches(colons.lowercase().replace(":", " "), hex))
        assertEquals(colons, CertTrust.format(hex))
    }

    @Test fun `a short or different fingerprint never matches`() {
        assertFalse(CertTrust.matches(hex, hex.dropLast(2)))
        assertFalse(CertTrust.matches(hex, hex.dropLast(1) + "0"))
        assertFalse(CertTrust.matches("", ""))
        assertFalse(CertTrust.isValid("abcd"))
    }

    @Test fun `hashes certificate bytes with sha 256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CertTrust.sha256Hex("abc".toByteArray()))
    }

    @Test fun `host and scheme of a server address`() {
        assertEquals("192.168.1.20", CertTrust.hostOf("https://192.168.1.20:8443/"))
        assertEquals("romm.home.lan", CertTrust.hostOf("https://ROMM.home.lan"))
        assertEquals("", CertTrust.hostOf("not a url"))
        assertTrue(CertTrust.isHttps("HTTPS://x"))
        assertFalse(CertTrust.isHttps("http://x"))
    }
}
