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
}
