package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Blocking streams run on the download's IO dispatcher, but still observe its own cancellation. */
object DownloadStreams {
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int,
        onBytesWritten: suspend (Int) -> Unit
    ) {
        val downloadContext = currentCoroutineContext()
        val buffer = ByteArray(bufferSize)
        while (true) {
            downloadContext.ensureActive()
            val count = input.read(buffer)
            // A pause may arrive during a blocking read. Do not write that chunk or report done.
            downloadContext.ensureActive()
            if (count < 0) break
            if (count == 0) continue
            try {
                output.write(buffer, 0, count)
            } catch (e: IOException) {
                throw StorageException("Could not write downloaded data: ${e.message}", e)
            }
            onBytesWritten(count)
        }
        downloadContext.ensureActive()
    }
}
