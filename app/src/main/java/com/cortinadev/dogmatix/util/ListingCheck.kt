package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.UrlEntry
import java.security.MessageDigest

/**
 * Deciding whether a source's listing changed since the last scan, so an unchanged one keeps its
 * rows (and their "new" dates) and costs no parsing or database work:
 *  - a web directory is asked with `If-None-Match` / `If-Modified-Since` when the server gave an
 *    `ETag` / `Last-Modified` before (a 304 then skips the download too); otherwise the listing is
 *    downloaded and compared with the previous one by hash;
 *  - a magnet link names its content by hash, so a magnet that was indexed before is not fetched
 *    again at all.
 * Any change to how the source is set up (content type, folders) or to how listings are read
 * ([PARSER_VERSION]) makes the next scan a full one.
 */
object ListingCheck {

    /** Raise when a change to parsing should re-read every listing once. */
    const val PARSER_VERSION = 1

    /** What the source's rows depend on besides the listing itself. */
    fun fingerprint(entry: UrlEntry): String =
        "v$PARSER_VERSION|${entry.contentType.name}|${entry.folders.sorted().joinToString(",")}"

    data class Previous(
        val ok: Boolean,
        val fingerprint: String?,
        val etag: String?,
        val lastModified: String?,
        val bodyHash: String?,
        val servedBy: String?,
        /** Rows the source has in the database right now. */
        val rows: Int,
        /** Games the last successful scan gave. */
        val files: Int
    )

    /** True when the previous scan's rows can be trusted for [entry] (not forced, same set-up, rows still there). */
    fun canReuse(entry: UrlEntry, previous: Previous?, force: Boolean): Boolean =
        !force && previous != null && previous.ok && previous.fingerprint == fingerprint(entry) &&
            (previous.rows > 0 || previous.files == 0) && previous.rows == previous.files

    /** Conditional request headers for [target], when the last answer came from that same address. */
    fun conditionalHeaders(entry: UrlEntry, previous: Previous?, force: Boolean, target: String): Map<String, String> {
        if (!canReuse(entry, previous, force)) return emptyMap()
        if ((previous!!.servedBy ?: entry.url) != target) return emptyMap()
        return buildMap {
            previous.etag?.let { put("If-None-Match", it) }
            previous.lastModified?.let { put("If-Modified-Since", it) }
        }
    }

    /** The same listing as last time, judged by its bytes. */
    fun sameBody(entry: UrlEntry, previous: Previous?, force: Boolean, bodyHash: String): Boolean =
        canReuse(entry, previous, force) && previous!!.bodyHash == bodyHash

    /** A magnet indexed before (with the same set-up) need not be fetched again: its content cannot change. */
    fun canSkipMagnet(entry: UrlEntry, previous: Previous?, force: Boolean): Boolean =
        entry.url.startsWith("magnet:", ignoreCase = true) && Regex("xt=urn:btih:", RegexOption.IGNORE_CASE).containsMatchIn(entry.url) &&
            canReuse(entry, previous, force) && previous!!.rows > 0

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
