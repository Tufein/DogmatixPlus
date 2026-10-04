package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.BuildConfig
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal JSON-over-HTTP helper on `HttpURLConnection` (the app has no OkHttp), shared by the
 * TorBox and RomM clients. Same shape as `GameMetadataService.getJson`, plus method, headers
 * and a request body.
 */
object JsonHttp {

    class HttpException(val code: Int, val body: String?) : IOException("HTTP $code${body?.let { ": ${it.take(200)}" }.orEmpty()}")

    data class Response(val code: Int, val json: JsonElement?, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    val userAgent = "Dogmatix/${BuildConfig.VERSION_NAME}"

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(connection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeUnless { it.isJsonNull }
            return Response(code, json, text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A binary GET (save files, states): the whole body, or [HttpException] on a non-2xx status.
     * Refuses bodies above [maxBytes] so a wrong URL cannot fill the memory.
     */
    fun download(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long, readTimeoutMs: Int = 60_000): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(connection)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw HttpException(code, connection.errorStream?.bufferedReader()?.use { it.readText() })
            }
            if (connection.contentLengthLong > maxBytes) throw IOException("File too large (${connection.contentLengthLong} bytes)")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw IOException("File too large")
                }
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Like [request] but throws [HttpException] on a non-2xx status. */
    fun requireOk(response: Response): Response =
        if (response.ok) response else throw HttpException(response.code, response.body)

    fun formBody(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            .toByteArray(Charsets.UTF_8)

    /** `multipart/form-data` body + its Content-Type (TorBox ignores url-encoded fields). */
    fun multipartBody(fields: Map<String, String>): Pair<ByteArray, String> {
        val boundary = "----Dogmatix" + System.nanoTime()
        val sb = StringBuilder()
        fields.forEach { (k, v) ->
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8) to "multipart/form-data; boundary=$boundary"
    }

    /** `multipart/form-data` body carrying one file under [fieldName], + its Content-Type. */
    fun multipartFileBody(fieldName: String, fileName: String, bytes: ByteArray): Pair<ByteArray, String> {
        val boundary = "----Dogmatix" + System.nanoTime()
        // RFC 7578: the file name goes as a quoted string; quotes and line breaks cannot appear in it.
        val safeName = fileName.replace("\"", "%22").replace("\r", "").replace("\n", "")
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val out = ByteArrayOutputStream(bytes.size + 512)
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write(tail.toByteArray(Charsets.UTF_8))
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    const val FORM_URLENCODED = "application/x-www-form-urlencoded"
    const val JSON = "application/json"
}
