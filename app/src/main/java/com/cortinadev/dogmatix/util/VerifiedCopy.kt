package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest

/** Never trusts size alone, and never replaces a different file. Provider adapter owns staging. */
object VerifiedCopy {
    fun hash(input: InputStream, check: () -> Unit = {}): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            check()
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    fun transfer(input: InputStream, output: OutputStream, check: () -> Unit = {}, progress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source -> output.use { target ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                check()
                val n = source.read(buffer)
                if (n < 0) break
                target.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                progress(n.toLong())
            }
            target.flush()
        } }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    fun requireSame(expected: String, actual: String) {
        if (expected != actual) throw IOException("Copy verification failed")
    }
}
