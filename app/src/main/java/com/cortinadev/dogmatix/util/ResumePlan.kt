package com.cortinadev.dogmatix.util

/**
 * Whether an interrupted web download can carry on from its partial file. The request goes out
 * with `Range: bytes=<have>-` (and `If-Range` with the validator saved last time, so a file that
 * changed on the server is fetched whole); only a `206` whose `Content-Range` starts exactly at
 * the bytes on disk is appended to. A rejected partial response needs a new full request before
 * the destination may be replaced; its response body cannot be reused from offset zero.
 */
object ResumePlan {

    enum class Action { APPEND, RESTART }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    /** Validated response sizes; [bodyBytes] is null only when the full response has no known size. */
    data class Transfer(val startOffset: Long, val totalBytes: Long, val bodyBytes: Long?)

    /** `bytes 100-199/1000` (or an unknown total, `/` + `*`) → (100, 199, 1000 or null); null when it is not that shape. */
    fun parseContentRange(header: String?): ContentRange? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        // Avoid overflow in end - start + 1 and ranges outside the complete representation.
        if (end < start || end - start == Long.MAX_VALUE) return null
        val total = if (m.groupValues[3] == "*") null else m.groupValues[3].toLongOrNull() ?: return null
        if (total != null && (total <= 0 || end >= total)) return null
        return ContentRange(start, end, total)
    }

    /** [expectedTotal]: the size the library knows (≤ 0 = unknown); a different total means another file. */
    fun decide(partialBytes: Long, responseCode: Int, contentRange: String?, expectedTotal: Long = -1L): Action {
        if (partialBytes <= 0 || responseCode != 206) return Action.RESTART
        val range = parseContentRange(contentRange) ?: return Action.RESTART
        if (expectedTotal > 0 && range.total != null && range.total != expectedTotal) return Action.RESTART
        val total = range.total ?: expectedTotal.takeIf { it > 0 } ?: return Action.RESTART
        // We request bytes=<offset>- and process one response. A capped range would leave a
        // truncated file, even if that individual range arrived in full.
        return if (range.start == partialBytes && range.end == total - 1) Action.APPEND else Action.RESTART
    }

    /** Null means this response must be discarded without creating/truncating/appending a file. */
    fun transfer(partialBytes: Long, responseCode: Int, contentRange: String?, contentLength: Long, expectedTotal: Long = -1L): Transfer? {
        if (responseCode == 200) {
            if (contentLength == 0L && expectedTotal > 0) return null
            // HTML catalog sizes can be rounded. Only exact response metadata can prove
            // that a full response is too short or too long.
            val length = contentLength.takeIf { it >= 0 }
            return Transfer(0, length ?: -1L, length)
        }
        if (decide(partialBytes, responseCode, contentRange, expectedTotal) != Action.APPEND) return null
        val range = parseContentRange(contentRange) ?: return null
        val bodyBytes = range.end - range.start + 1
        if (contentLength >= 0 && contentLength != bodyBytes) return null
        return Transfer(partialBytes, range.total ?: expectedTotal, bodyBytes)
    }

    /** The validator to send back as `If-Range`: a strong ETag, else Last-Modified; null when neither is usable. */
    fun validator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }
}
