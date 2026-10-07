package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.model.DownloadFailureCategory
import com.cortinadev.dogmatix.data.service.DownloadHttpClient
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.DownloadStreams
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises Android's actual HttpURLConnection against a local, deterministic HTTP server. */
class HttpDownloadRegressionTest {
    @Test fun invalidPartialRepliesFetchAWholeBodyBeforeReplacingTheSavedPartial() = runBlocking {
        val invalidReplies = listOf(
            Response(206, mapOf("Content-Range" to "bytes 5-9/10"), "fghij"), // wrong offset
            Response(206, mapOf("Content-Range" to "bytes 4-10/11"), "efghijk"), // changed size
            Response(206, mapOf("Content-Range" to "bytes 4-7/10"), "efgh"), // capped range
            Response(206, mapOf("Content-Range" to "bytes 4-9/10", "Content-Length" to "5"), "efghij"),
            Response(206, emptyMap(), "efghij") // missing range metadata
        )
        for (invalidReply in invalidReplies) {
            HttpFixture { _, request -> if (request == 1) invalidReply else Response(200, emptyMap(), "abcdefghij") }.use { server ->
                val partial = ByteArrayOutputStream().apply { write("abcd".toByteArray()) }
                val response = DownloadHttpClient().openDownload(
                    server.url, rangeStart = partial.size().toLong(), headers = mapOf("If-Range" to "\"original\""), expectedTotal = 10
                )
                try {
                    // The rejected response never reached a writable destination. A validated
                    // full response now permits replacing it, using the same offset as the worker.
                    assertArrayEquals("abcd".toByteArray(), partial.toByteArray())
                    assertEquals(0L, response.transfer.startOffset)
                    partial.reset()
                    response.connection.inputStream.use { input ->
                        DownloadStreams.copy(input, partial, 8, expectedBytes = response.transfer.bodyBytes) {}
                    }
                    assertArrayEquals("abcdefghij".toByteArray(), partial.toByteArray())
                    assertEquals(2, server.requests.size)
                    assertEquals("bytes=4-", server.requests[0]["range"])
                    assertEquals("\"original\"", server.requests[0]["if-range"])
                    assertNull(server.requests[1]["range"])
                    assertNull(server.requests[1]["if-range"])
                } finally { response.connection.disconnect() }
            }
        }
    }

    @Test fun aValidChunkedRangeAppendsExactlyTheRemainingBytes() = runBlocking {
        HttpFixture { _, _ -> Response(206, mapOf("Content-Range" to "bytes 4-9/10", "Transfer-Encoding" to "chunked"), "6\r\nefghij\r\n0\r\n\r\n") }.use { server ->
            val output = ByteArrayOutputStream().apply { write("abcd".toByteArray()) }
            val response = DownloadHttpClient().openDownload(server.url, rangeStart = 4, expectedTotal = 10)
            try {
                assertEquals(4L, response.transfer.startOffset)
                assertEquals(10L, response.transfer.totalBytes)
                assertEquals(6L, response.transfer.bodyBytes)
                response.connection.inputStream.use { input ->
                    DownloadStreams.copy(input, output, 8, expectedBytes = response.transfer.bodyBytes) {}
                }
                assertArrayEquals("abcdefghij".toByteArray(), output.toByteArray())
                assertEquals(1, server.requests.size)
            } finally { response.connection.disconnect() }
        }
    }

    @Test fun anUnsatisfiedRangeFetchesTheWholeFileWithoutRangeHeaders() = runBlocking {
        HttpFixture { _, request ->
            if (request == 1) Response(416, mapOf("Content-Range" to "bytes */10"), "")
            else Response(200, emptyMap(), "abcdefghij")
        }.use { server ->
            val response = DownloadHttpClient().openDownload(server.url, rangeStart = 20, headers = mapOf("If-Range" to "\"old\""), expectedTotal = 10)
            try {
                assertEquals(0L, response.transfer.startOffset)
                assertEquals("abcdefghij", response.connection.inputStream.use { String(it.readBytes()) })
                assertEquals(2, server.requests.size)
                assertNull(server.requests.last()["range"])
                assertNull(server.requests.last()["if-range"])
            } finally { response.connection.disconnect() }
        }
    }

    @Test fun anUnexpectedPartialResponseCannotReplaceTheSavedFileEvenAfter416() {
        for (firstCode in listOf(206, 416)) {
            HttpFixture { _, request ->
                if (firstCode == 416 && request == 1) Response(416, emptyMap(), "")
                else Response(206, mapOf("Content-Range" to "bytes 4-9/10"), "efghij")
            }.use { server ->
                val savedPartial = ByteArrayOutputStream().apply { write("abcd".toByteArray()) }
                var writable = false
                try {
                    val response = DownloadHttpClient().openDownload(server.url, rangeStart = if (firstCode == 416) 20 else 0, expectedTotal = 10)
                    response.connection.disconnect()
                    writable = true
                } catch (e: IOException) {
                    assertEquals(DownloadFailureCategory.NETWORK, DownloadFailures.classify(e)?.category)
                }
                assertFalse("Unexpected 206 reached the output stage", writable)
                assertArrayEquals("abcd".toByteArray(), savedPartial.toByteArray())
                assertEquals(if (firstCode == 416) 2 else 1, server.requests.size)
            }
        }
    }

    @Test fun aCleanChunkedEofCannotFinishAnAnnouncedRangeEarly() = runBlocking {
        HttpFixture { _, _ -> Response(206, mapOf("Content-Range" to "bytes 4-9/10", "Transfer-Encoding" to "chunked"), "3\r\nefg\r\n0\r\n\r\n") }.use { server ->
            val response = DownloadHttpClient().openDownload(server.url, rangeStart = 4, expectedTotal = 10)
            var completed = false
            val output = ByteArrayOutputStream()
            try {
                response.connection.inputStream.use { input ->
                    DownloadStreams.copy(input, output, 8, expectedBytes = response.transfer.bodyBytes) {}
                }
                completed = true
            } catch (e: EOFException) {
                assertEquals(DownloadFailureCategory.NETWORK, DownloadFailures.classify(e)?.category)
            } finally { response.connection.disconnect() }
            assertFalse("Truncated HTTP data reached completion", completed)
            assertArrayEquals("efg".toByteArray(), output.toByteArray())
        }
    }

    @Test fun aFullChunkedResponseDoesNotTreatRoundedCatalogSizeAsAnExactLength() = runBlocking {
        HttpFixture { _, _ -> Response(200, mapOf("Transfer-Encoding" to "chunked"), "3\r\nabc\r\n0\r\n\r\n") }.use { server ->
            val response = DownloadHttpClient().openDownload(server.url, expectedTotal = 4)
            try {
                assertNull(response.transfer.bodyBytes)
                val output = ByteArrayOutputStream()
                response.connection.inputStream.use { input ->
                    DownloadStreams.copy(input, output, 8, expectedBytes = response.transfer.bodyBytes) {}
                }
                assertArrayEquals("abc".toByteArray(), output.toByteArray())
            } finally { response.connection.disconnect() }
        }
    }

    private data class Response(val code: Int, val headers: Map<String, String>, val body: String)

    /** No remote dependency or lingering worker/socket after a test. Each response closes its socket. */
    private class HttpFixture(private val respond: (Map<String, String>, Int) -> Response) : Closeable {
        private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        private val active = AtomicReference<Socket?>()
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        val url = "http://127.0.0.1:${listener.localPort}/game.gba"
        private val worker = Thread {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        check(reader.readLine()?.startsWith("GET ") == true)
                        val request = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            request[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        requests += request
                        val response = respond(request, requests.size)
                        val body = response.body.toByteArray(Charsets.US_ASCII)
                        val headers = response.headers.toMutableMap()
                        if (headers.keys.none { it.equals("Content-Length", true) || it.equals("Transfer-Encoding", true) }) {
                            headers["Content-Length"] = body.size.toString()
                        }
                        headers["Connection"] = "close"
                        try {
                            socket.getOutputStream().apply {
                                write(buildString {
                                    append("HTTP/1.1 ${response.code} Fixture\r\n")
                                    headers.forEach { (key, value) -> append("$key: $value\r\n") }
                                    append("\r\n")
                                }.toByteArray(Charsets.US_ASCII))
                                write(body)
                                flush()
                            }
                        } catch (_: SocketException) {
                            // Rejecting headers deliberately disconnects without consuming the
                            // body. Keep serving the subsequent full request after that peer reset.
                        }
                        active.set(null)
                    }
                }
            } catch (e: Exception) {
                if (!listener.isClosed) failure.set(e)
            }
        }.apply { name = "http-download-fixture"; isDaemon = true; start() }

        override fun close() {
            listener.close()
            active.getAndSet(null)?.close()
            worker.join(2_000)
            assertFalse("Local HTTP fixture did not stop", worker.isAlive)
            assertNull("Local HTTP fixture failed: ${failure.get()}", failure.get())
        }
    }
}
