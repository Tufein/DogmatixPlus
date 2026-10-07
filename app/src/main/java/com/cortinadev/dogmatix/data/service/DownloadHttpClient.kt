package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.ResumePlan
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** A server answer other than 200 / 206; the message is what the diagnostics have always shown. */
class HttpStatusException(val code: Int) : Exception("HTTP Error after redirect: $code")

data class HttpDownloadResponse(val connection: HttpURLConnection, val transfer: ResumePlan.Transfer)

@Singleton
class DownloadHttpClient @Inject constructor() {
    /** Only return a body that is safe to write from [HttpDownloadResponse.transfer]'s offset. */
    fun openDownload(downloadUrl: String, rangeStart: Long = 0L, headers: Map<String, String> = emptyMap(), expectedTotal: Long = -1L): HttpDownloadResponse {
        val connection = createConnection(downloadUrl, rangeStart, headers)
        try {
            val transfer = ResumePlan.transfer(rangeStart, connection.responseCode,
                connection.getHeaderField("Content-Range"), connection.contentLengthLong, expectedTotal)
            if (transfer != null) return HttpDownloadResponse(connection, transfer)
            // A server may ignore/misinterpret a Range or advertise a changed/capped file. Do
            // not write that response at offset zero: fetch a full body before replacing disk data.
            if (rangeStart > 0) {
                connection.disconnect()
                return openDownload(downloadUrl, 0L, headers - "If-Range", expectedTotal)
            }
            throw IOException("Server returned an invalid download response")
        } catch (e: Exception) {
            try { connection.disconnect() } catch (_: Exception) { }
            throw e
        }
    }

    /** [rangeStart] > 0 asks for the rest of the file; [openDownload] validates it before writing. */
    private fun createConnection(downloadUrl: String, rangeStart: Long, headers: Map<String, String>): HttpURLConnection {
        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        TlsTrust.apply(connection)

        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Wget/1.25.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "Keep-Alive")
        if (rangeStart > 0L) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.connectTimeout = Constants.CONNECTION_TIMEOUT_MS.toInt()
        connection.readTimeout = Constants.READ_TIMEOUT_MS.toInt()
        
        try {
            val redirectResponseCode = connection.responseCode
            // A 416 for a saved partial needs a new full request. openDownload handles that
            // together with malformed 206 replies, validating the new response at offset zero.
            if (redirectResponseCode != HttpURLConnection.HTTP_OK && redirectResponseCode != HttpURLConnection.HTTP_PARTIAL &&
                !(rangeStart > 0 && redirectResponseCode == 416)) {
                throw HttpStatusException(redirectResponseCode)
            }

            return connection
        } catch (e: Exception) {
            // A rejected response never reaches the worker's connection variable/finally.
            // Release it here, including authentication/server failures in a large batch.
            try { connection.disconnect() } catch (_: Exception) { }
            throw e
        }
    }
}
