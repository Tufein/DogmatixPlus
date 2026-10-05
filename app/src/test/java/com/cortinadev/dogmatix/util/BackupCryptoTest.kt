package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class BackupCryptoTest {

    /** Few rounds keep the tests fast; one test uses the real count. */
    private val fast = 1_000
    private val json = """{"format":"dogmatix-backup","version":1,"settings":{"romm_token":{"t":"s","v":"rmm_secret"}},"favourites":[]}"""

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.java.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
    }

    @Test fun `a sealed backup opens again with the same passphrase`() {
        val blob = BackupCrypto.seal(json, "correct horse battery", iterations = fast)
        assertTrue(BackupCrypto.isEncrypted(blob))
        assertEquals(json, BackupCrypto.open(blob, "correct horse battery", iterations = fast))
        // Nothing of the content is readable in the file.
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("rmm_secret"))
    }

    @Test fun `round trip with the real number of rounds`() {
        val blob = BackupCrypto.seal(json, "a long passphrase")
        assertEquals(json, BackupCrypto.open(blob, "a long passphrase"))
    }

    @Test fun `a wrong passphrase fails cleanly`() {
        val blob = BackupCrypto.seal(json, "correct horse battery", iterations = fast)
        assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.open(blob, "correct horse batterY", iterations = fast) }
        assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.open(blob, "", iterations = fast) }
    }

    @Test fun `any changed byte is detected`() {
        val blob = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", iterations = fast)
        val positions = listOf(
            BackupCrypto.MAGIC.size,                                   // salt
            BackupCrypto.MAGIC.size + BackupCrypto.SALT_BYTES,         // iv
            BackupCrypto.MAGIC.size + BackupCrypto.SALT_BYTES + BackupCrypto.IV_BYTES + 3, // ciphertext
            blob.size - 1                                              // tag
        )
        for (p in positions) {
            val tampered = blob.copyOf().also { it[p] = (it[p].toInt() xor 0x01).toByte() }
            assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.decrypt(tampered, "passphrase!", iterations = fast) }
        }
        val badMagic = blob.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows<BackupCrypto.NotEncryptedException> { BackupCrypto.decrypt(badMagic, "passphrase!", iterations = fast) }
    }

    @Test fun `a truncated file is unreadable, not garbage`() {
        val blob = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", iterations = fast)
        assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.decrypt(blob.copyOf(blob.size - 5), "passphrase!", iterations = fast) }
        assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.decrypt(blob.copyOf(20), "passphrase!", iterations = fast) }
        assertThrows<BackupCrypto.UnreadableException> { BackupCrypto.decrypt(BackupCrypto.MAGIC, "passphrase!", iterations = fast) }
    }

    @Test fun `a plain JSON backup is not an encrypted one`() {
        assertFalse(BackupCrypto.isEncrypted(json.toByteArray()))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
        assertThrows<BackupCrypto.NotEncryptedException> { BackupCrypto.open(json.toByteArray(), "x", iterations = fast) }
    }

    @Test fun `the file layout is magic, salt, iv, ciphertext and tag`() {
        val plain = ByteArray(100) { it.toByte() }
        val blob = BackupCrypto.encrypt(plain, "passphrase!", iterations = fast)
        assertEquals("DGXB1", String(blob.copyOfRange(0, 5), Charsets.US_ASCII))
        assertEquals(5 + 16 + 12 + 100 + 16, blob.size)
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "passphrase!", iterations = fast))
    }

    @Test fun `every file gets its own salt and iv`() {
        val a = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", iterations = fast)
        val b = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", iterations = fast)
        assertNotEquals(hex(a.copyOfRange(5, 33)), hex(b.copyOfRange(5, 33)))
        assertNotEquals(hex(a), hex(b))
        // Same random source → same file (the format itself is deterministic).
        val c = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }, fast)
        val d = BackupCrypto.encrypt(json.toByteArray(), "passphrase!", SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }, fast)
        assertEquals(hex(c), hex(d))
    }

    @Test fun `the passphrase is trimmed and Unicode-normalized`() {
        val composed = "café au lait"
        val decomposed = "café au lait"
        val blob = BackupCrypto.seal(json, " $composed ", iterations = fast)
        assertEquals(json, BackupCrypto.open(blob, decomposed, iterations = fast))
    }

    @Test fun `key derivation is standard PBKDF2-HMAC-SHA256 (RFC 7914 vectors)`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            hex(BackupCrypto.deriveKey("passwd", "salt".toByteArray(), iterations = 1))
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            hex(BackupCrypto.deriveKey("Password", "NaCl".toByteArray(), iterations = 80_000))
        )
    }

    @Test fun `short passphrases are refused`() {
        assertFalse(BackupCrypto.isAcceptable("1234567"))
        assertFalse(BackupCrypto.isAcceptable("   abc    "))
        assertTrue(BackupCrypto.isAcceptable("12345678"))
    }
}
