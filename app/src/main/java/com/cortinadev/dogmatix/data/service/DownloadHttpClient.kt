package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.Constants
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** A server answer other than 200 / 206; the message is what the diagnostics have always shown. */
class HttpStatusException(val code: Int) : Exception("HTTP Error after redirect: $code")

@Singleton
class DownloadHttpClient @Inject constructor() {
    /** [rangeStart] > 0 asks for the rest of the file; the caller checks for 206 before appending. */
    fun createConnection(downloadUrl: String, rangeStart: Long = 0L, headers: Map<String, String> = emptyMap()): HttpURLConnection {
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
        
        val redirectResponseCode = connection.responseCode
        // 416: the partial file is already as long as the file (or longer), so there is nothing to
        // continue from. Ask for the whole file instead; the caller then starts over (a 200, not a 206).
        if (rangeStart > 0L && redirectResponseCode == 416) {
            connection.disconnect()
            return createConnection(downloadUrl, 0L, headers - "If-Range")
        }
        if (redirectResponseCode != HttpURLConnection.HTTP_OK && redirectResponseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw HttpStatusException(redirectResponseCode)
        }
        
        return connection
    }
}
