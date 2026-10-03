package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumsTest {
    private val abc = "abc".toByteArray()

    @Test fun `known hashes of abc`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Checksums.hexOf(abc.inputStream(), HashAlgo.SHA1))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Checksums.hexOf(abc.inputStream(), HashAlgo.MD5))
        assertEquals("352441c2", Checksums.hexOf(abc.inputStream(), HashAlgo.CRC32))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.hexOf(abc.inputStream(), HashAlgo.SHA256))
    }

    @Test fun `specs parse and are validated`() {
        assertEquals(ExpectedHash(HashAlgo.SHA1, "a9993e364706816aba3e25717850c26c9cd0d89d"), Checksums.parse("SHA1:A9993E364706816ABA3E25717850C26C9CD0D89D"))
        assertNull(Checksums.parse("sha1:tooshort"))
        assertNull(Checksums.parse("sha1:zz993e364706816aba3e25717850c26c9cd0d89d"))
        assertNull(Checksums.parse("whirlpool:abcd"))
        assertNull(Checksums.parse(null))
        assertNull(Checksums.parse("nocolon"))
    }

    @Test fun `the strongest listed hash is used`() {
        assertEquals("sha1:a9993e364706816aba3e25717850c26c9cd0d89d", Checksums.best("a9993e364706816aba3e25717850c26c9cd0d89d", "900150983cd24fb0d6963f7d28e17f72", "352441c2"))
        assertEquals("md5:900150983cd24fb0d6963f7d28e17f72", Checksums.best("", "900150983cd24fb0d6963f7d28e17f72", "352441c2"))
        assertEquals("crc32:352441c2", Checksums.best(null, "", "352441C2"))
        assertNull(Checksums.best("", null, ""))
    }

    @Test fun `a match ignores case`() {
        val expected = Checksums.parse("md5:900150983CD24FB0D6963F7D28E17F72")!!
        assertTrue(Checksums.matches(expected, "900150983cd24fb0d6963f7d28e17f72"))
        assertFalse(Checksums.matches(expected, "00000000000000000000000000000000"))
    }

    @Test fun `headers a server may send`() {
        // base64 of the md5 of "abc"
        assertEquals("md5:900150983cd24fb0d6963f7d28e17f72", Checksums.fromContentMd5("kAFQmDzST7DWlj99KOF/cg=="))
        assertNull(Checksums.fromContentMd5("not base64!"))
        assertNull(Checksums.fromContentMd5(null))
        val sha256 = "sha-256=ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.fromDigestHeader("md5=kAFQmDzST7DWlj99KOF/cg==, $sha256"))
        assertNull(Checksums.fromDigestHeader("unixsum=1234"))
    }
}
