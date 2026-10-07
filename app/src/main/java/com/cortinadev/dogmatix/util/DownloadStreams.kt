package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.io.IOException
import java.io.EOFException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Blocking streams run on the download's IO dispatcher, but still observe its own cancellation. */
object DownloadStreams {
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int,
        expectedBytes: Long? = null,
        onBytesWritten: suspend (Int) -> Unit
    ) {
        require(expectedBytes == null || expectedBytes >= 0)
        val downloadContext = currentCoroutineContext()
        val buffer = ByteArray(bufferSize)
        var written = 0L
        while (true) {
            downloadContext.ensureActive()
            val count = input.read(buffer)
            // A pause may arrive during a blocking read. Do not write that chunk or report done.
            downloadContext.ensureActive()
            if (count < 0) break
            if (count == 0) continue
            if (count > Long.MAX_VALUE - written || (expectedBytes != null && count > expectedBytes - written)) {
                throw IOException("Download response exceeds its expected length")
            }
            try {
                output.write(buffer, 0, count)
            } catch (e: IOException) {
                throw StorageException("Could not write downloaded data: ${e.message}", e)
            }
            written += count
            onBytesWritten(count)
        }
        downloadContext.ensureActive()
        if (expectedBytes != null && written != expectedBytes) {
            throw EOFException("Incomplete download response: $written of $expectedBytes bytes")
        }
    }
}
