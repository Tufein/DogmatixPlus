package com.cortinadev.dogmatix.util

/** A file read from a WebDAV server, with its ETag when the server gave a strong one. */
class DavFile(val bytes: ByteArray, val etag: String?)

/**
 * The WebDAV operations the cloud backup and device sync need, so their logic
 * ([CloudBackupEngine], [DeviceSyncEngine]) runs against a fake in the tests and against
 * [com.cortinadev.dogmatix.data.service.WebDavClient] in the app. Every call is blocking and
 * fails with a [DavException].
 */
interface DavStore {
    /** The resource [url] (`depth` 0) or it and its members (`depth` 1). */
    fun propfind(url: String, depth: Int): List<WebDavXml.Entry>

    /** The members of the collection [url] (itself left out); empty when it does not exist. */
    fun list(url: String): List<WebDavXml.Entry>

    fun exists(url: String): Boolean

    /** The file at [url]; null when there is none. */
    fun get(url: String, maxBytes: Long): DavFile?

    /**
     * Writes [bytes] to [url]; with [ifMatch] only if the file still has that ETag, with
     * [ifNoneMatch] only if there is no file yet ([DavProblem.PRECONDITION] otherwise).
     * Returns the new ETag when known.
     */
    fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String? = null, ifNoneMatch: Boolean = false): String?

    /** Creates the collection [url] and its missing parents below [root]; true when something was created. */
    fun ensureCollection(url: String, root: String): Boolean

    /** Deletes [url]; already gone counts as done. */
    fun delete(url: String)

    /** Where the last request really ended (after same-host redirects). */
    val lastUrl: String
}
