package com.cortinadev.dogmatix.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStreamsTest {
    @Test fun `an unlimited transfer observes cancellation before the next chunk`() = runBlocking {
        val output = ByteArrayOutputStream()
        var completed = false
        val download = launch(start = CoroutineStart.LAZY) {
            DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 1) {
                currentCoroutineContext()[Job]!!.cancel()
            }
            completed = true
        }
        download.start()
        download.join()
        assertTrue(download.isCancelled)
        assertArrayEquals(byteArrayOf(1), output.toByteArray())
        assertFalse(completed)
    }

    @Test fun `cancellation during a read never writes its chunk or reports completion`() = runBlocking {
        val output = ByteArrayOutputStream()
        var completed = false
        var readCount = 0
        val download = launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]!!
            val input = object : InputStream() {
                override fun read(): Int = error("buffered read expected")
                override fun read(buffer: ByteArray): Int {
                    readCount++
                    buffer[0] = 1
                    owner.cancel()
                    return 1
                }
            }
            DownloadStreams.copy(input, output, 8) { error("Canceled chunk was written") }
            completed = true
        }
        download.start()
        download.join()
        assertEquals(1, readCount)
        assertEquals(0, output.size())
        assertFalse(completed)
    }

    @Test fun `a failed storage write is classified as a storage failure`() = runBlocking {
        val failure = IOException("Disk full")
        val output = object : OutputStream() {
            override fun write(value: Int): Unit = throw failure
        }
        try {
            DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(1)), output, 8) {}
            error("Expected storage failure")
        } catch (e: StorageException) {
            assertEquals(failure, e.cause)
        }
    }

    @Test fun `a failed network read remains a source failure`() = runBlocking {
        val failure = IOException("Connection lost")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        try {
            DownloadStreams.copy(input, ByteArrayOutputStream(), 8) {}
            error("Expected network failure")
        } catch (e: IOException) {
            assertEquals(failure, e)
        }
    }

    @Test fun `a complete transfer reports exactly the bytes stored`() = runBlocking {
        val bytes = ByteArray(100) { it.toByte() }
        val output = ByteArrayOutputStream()
        var reported = 0
        DownloadStreams.copy(ByteArrayInputStream(bytes), output, 13) { reported += it }
        assertEquals(bytes.size, reported)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun `a clean short EOF cannot report a complete download`() = runBlocking {
        val output = ByteArrayOutputStream()
        var completed = false
        try {
            DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 8, expectedBytes = 4) {}
            completed = true
        } catch (_: EOFException) { }
        assertFalse(completed)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test fun `an oversized response is refused before writing beyond the announced body`() = runBlocking {
        val output = ByteArrayOutputStream()
        var reported = 0
        try {
            DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 1, expectedBytes = 2) { reported += it }
            error("Expected oversized response failure")
        } catch (_: IOException) { }
        assertEquals(2, reported)
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }

    @Test fun `a resumed body is checked against remaining bytes rather than the whole file`() = runBlocking {
        val output = ByteArrayOutputStream().apply { write(byteArrayOf(1, 2)) }
        DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(3, 4)), output, 8, expectedBytes = 2) {}
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test fun `zero length and unknown length full responses remain supported`() = runBlocking {
        DownloadStreams.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 8, expectedBytes = 0) {}
        val output = ByteArrayOutputStream()
        DownloadStreams.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 8, expectedBytes = null) {}
        assertEquals(3, output.size())
    }
}
