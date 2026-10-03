package com.cortinadev.dogmatix.util

import java.security.MessageDigest

/**
 * Helpers for trusting one specific server certificate (a RomM server with a self-signed or
 * private-CA certificate): the user is shown its SHA-256 fingerprint once, confirms it, and from
 * then on only a certificate with exactly that fingerprint is accepted for that server.
 */
object CertTrust {

    /** SHA-256 of a DER-encoded certificate as 64 lower-case hex characters. */
    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** `AA:BB:CC…` for display. */
    fun format(hex: String): String =
        normalize(hex).chunked(2).joinToString(":").uppercase()

    /** Lower-case hex without separators or spaces, so a pasted `AA:BB` and a stored `aabb` compare equal. */
    fun normalize(fingerprint: String): String =
        fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun isValid(fingerprint: String): Boolean = normalize(fingerprint).length == 64

    /** True when both are valid fingerprints and the same one. */
    fun matches(expected: String, actual: String): Boolean {
        val a = normalize(expected)
        val b = normalize(actual)
        return a.length == 64 && a == b
    }

    /** Host part of a server URL, lower-case; "" when there is none. */
    fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)
}
