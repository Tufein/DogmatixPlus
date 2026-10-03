package com.cortinadev.dogmatix.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a source could not be scanned, in words the scan report can show. */
enum class FailureKind {
    /** The server asks to slow down (HTTP 429). */
    RATE_LIMITED,
    /** The server had a problem (5xx). */
    SERVER_ERROR,
    /** The address does not exist (404, 410). */
    NOT_FOUND,
    /** Access refused (401, 403) — often a bot check in front of the site. */
    FORBIDDEN,
    TIMEOUT,
    /** No connection, unknown host, connection reset. */
    NETWORK,
    /** The page has no file table: the site changed, or an error / challenge page came back. */
    NO_TABLE,
    /** A torrent's file list did not arrive in time. */
    TORRENT_METADATA,
    OTHER
}

/** A source that failed in a scan. */
data class ScanFailure(
    val consoleId: String,
    val consoleName: String,
    val url: String,
    val kind: FailureKind,
    val httpCode: Int? = null,
    val detail: String = ""
)

/** An HTTP answer that was not a listing, with what the server said about trying again. */
class ScrapeHttpException(val code: Int, val retryAfterSeconds: Long?, url: String) :
    IOException("HTTP $code for $url")

/** A page that came back fine but holds no file table. */
class NoFileTableException(url: String) :
    IOException("No file table at $url (the site may have changed, or returned an error or bot-check page)")

object ScanFailures {

    fun kindOf(error: Throwable): FailureKind {
        val chain = generateSequence(error) { it.cause }.toList()
        chain.firstNotNullOfOrNull { it as? ScrapeHttpException }?.let { return kindOfCode(it.code) }
        return when {
            chain.any { it is NoFileTableException } -> FailureKind.NO_TABLE
            chain.any { it.javaClass.simpleName == "TorrentMetadataTimeoutException" } -> FailureKind.TORRENT_METADATA
            chain.any { it is SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") } -> FailureKind.TIMEOUT
            chain.any { it is UnknownHostException || it is SSLException || it is java.net.ConnectException || it is java.net.SocketException } -> FailureKind.NETWORK
            chain.any { it is IOException } -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
    }

    fun kindOfCode(code: Int): FailureKind = when (code) {
        429 -> FailureKind.RATE_LIMITED
        401, 403 -> FailureKind.FORBIDDEN
        404, 410 -> FailureKind.NOT_FOUND
        408 -> FailureKind.TIMEOUT
        in 500..599 -> FailureKind.SERVER_ERROR
        else -> FailureKind.OTHER
    }

    fun httpCodeOf(error: Throwable): Int? =
        generateSequence(error) { it.cause }.firstNotNullOfOrNull { it as? ScrapeHttpException }?.code

    /** Worth another try later in the same scan: the server or the network had a bad moment. */
    fun isRetryable(kind: FailureKind): Boolean =
        kind == FailureKind.RATE_LIMITED || kind == FailureKind.SERVER_ERROR || kind == FailureKind.TIMEOUT || kind == FailureKind.NETWORK

    /**
     * How long to wait before attempt [attempt] (1 = the first retry): what the server asked for
     * (`Retry-After`, capped at two minutes), else 3, 6, 12, 24 s.
     */
    fun backoffMillis(attempt: Int, retryAfterSeconds: Long?): Long =
        retryAfterSeconds?.takeIf { it > 0 }?.let { minOf(it, 120L) * 1000 }
            ?: (3_000L shl (attempt - 1).coerceIn(0, 4))

    /** `Retry-After` as seconds: a number, or an HTTP date relative to [now]. */
    fun parseRetryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((at - now) / 1000).coerceAtLeast(0)
        }.getOrNull()
    }
}
