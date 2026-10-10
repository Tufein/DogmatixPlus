package com.cortinadev.dogmatix.util

import java.security.MessageDigest

/** Small, platform-independent parts of the update preflight. */
object UpdateInstallability {
    /** Android's certificate bytes rendered in the same form as apksigner. */
    fun certificateDigest(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { "%02x".format(it) }

    fun hasMatchingCertificate(installed: Collection<ByteArray>, downloaded: Collection<ByteArray>): Boolean {
        if (installed.isEmpty() || downloaded.isEmpty()) return false
        val expected = installed.map(::certificateDigest).toSet()
        return downloaded.map(::certificateDigest).toSet() == expected
    }
}
