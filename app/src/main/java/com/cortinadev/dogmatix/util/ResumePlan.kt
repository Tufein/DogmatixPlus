package com.cortinadev.dogmatix.util

/**
 * Whether an interrupted web download can carry on from its partial file. The request goes out
 * with `Range: bytes=<have>-` (and `If-Range` with the validator saved last time, so a file that
 * changed on the server is fetched whole); only a `206` whose `Content-Range` starts exactly at
 * the bytes on disk is appended to. Anything else starts over from zero. Pure JVM for the tests.
 */
object ResumePlan {

    enum class Action { APPEND, RESTART }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    /** `bytes 100-199/1000` (or an unknown total, `/` + `*`) → (100, 199, 1000 or null); null when it is not that shape. */
    fun parseContentRange(header: String?): ContentRange? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        return ContentRange(start, end, m.groupValues[3].toLongOrNull())
    }

    /** [expectedTotal]: the size the library knows (≤ 0 = unknown); a different total means another file. */
    fun decide(partialBytes: Long, responseCode: Int, contentRange: String?, expectedTotal: Long = -1L): Action {
        if (partialBytes <= 0 || responseCode != 206) return Action.RESTART
        val range = parseContentRange(contentRange) ?: return Action.RESTART
        if (expectedTotal > 0 && range.total != null && range.total != expectedTotal) return Action.RESTART
        return if (range.start == partialBytes) Action.APPEND else Action.RESTART
    }

    /** The validator to send back as `If-Range`: a strong ETag, else Last-Modified; null when neither is usable. */
    fun validator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }
}
