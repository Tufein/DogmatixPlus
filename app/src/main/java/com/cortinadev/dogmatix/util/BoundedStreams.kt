package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.io.ByteArrayOutputStream

object BoundedStreams {
    fun read(input: InputStream, maximum: Int): ByteArray {
        require(maximum >= 0)
        val output = ByteArrayOutputStream(minOf(maximum, 8192))
        val buffer = ByteArray(minOf(maximum.coerceAtLeast(1), 8192))
        while (output.size() < maximum) {
            val count = input.read(buffer, 0, minOf(buffer.size, maximum - output.size()))
            if (count < 0) break
            if (count == 0) { val byte = input.read(); if (byte < 0) break; output.write(byte) }
            else output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * All of [input], a file of [expectedSize] bytes (0 or less = not known), at most [maxBytes].
     * With a known size the bytes go straight into one array of exactly that size, so a large file
     * (a 64 MB memory card) is held once in memory instead of being copied while a buffer grows.
     * Throws [tooLarge] past [maxBytes], and [changed] when the stream is shorter or longer than
     * [expectedSize] (the file changed while it was read). [check] runs between chunks (cancellation).
     */
    fun readAll(
        input: InputStream,
        expectedSize: Long,
        maxBytes: Long,
        tooLarge: () -> Exception,
        changed: () -> Exception,
        check: () -> Unit = {}
    ): ByteArray {
        if (expectedSize > maxBytes || expectedSize > MAX_ARRAY) throw tooLarge()
        if (expectedSize <= 0L) {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size().toLong() + count > minOf(maxBytes, MAX_ARRAY)) throw tooLarge()
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
        val bytes = ByteArray(expectedSize.toInt())
        var filled = 0
        while (filled < bytes.size) {
            check()
            val count = input.read(bytes, filled, minOf(CHUNK, bytes.size - filled))
            if (count < 0) throw changed()
            if (count == 0) {
                // A stream that answers 0 instead of blocking: one byte at a time.
                val byte = input.read()
                if (byte < 0) throw changed()
                bytes[filled++] = byte.toByte()
            } else filled += count
        }
        if (input.read() >= 0) throw changed()
        return bytes
    }

    private const val CHUNK = 64 * 1024
    /** The longest array a JVM reliably allocates. */
    private const val MAX_ARRAY = Int.MAX_VALUE - 16L
}
