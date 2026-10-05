package com.cortinadev.dogmatix.util

/**
 * An in-memory WebDAV server for the cloud tests: collections and files by decoded path, ETags
 * that change on every write, and switches for the misbehaviour real servers show.
 */
class FakeDavStore(
    /** Decoded paths of collections that exist from the start (the server's own folders). */
    vararg existing: String
) : DavStore {
    val collections = sortedSetOf<String>()
    val files = sortedMapOf<String, Pair<ByteArray, String>>()
    private var etagCounter = 0
    var requests = mutableListOf<String>()

    /** Paths that answer PROPFIND with this problem instead (a web page, a login wall…). */
    val failing = mutableMapOf<String, DavProblem>()
    /** The server refuses every conditional write (broken ETag handling behind some proxies). */
    var refuseConditionalWrites = false
    /** Fails the next PUT with this problem. */
    var failNextPut: DavProblem? = null
    /** Runs before a PUT is applied (to simulate another device writing at the same moment). */
    var beforePut: ((String) -> Unit)? = null
    /** A server that never sends an ETag. */
    var hideEtags = false
    /** A server that ignores If-Match / If-None-Match (every conditional write is a blind overwrite). */
    var ignoreConditions = false
    /** Runs once after the next GET returned (another device writing right after our read). */
    var afterGet: ((String) -> Unit)? = null
    /** Runs once after the next PUT was applied (another device overwriting right after us). */
    var afterPut: ((String) -> Unit)? = null
    /** A server without MOVE. */
    var moveUnsupported = false
    /** Fails the next MOVE with this problem. */
    var failNextMove: DavProblem? = null
    /** Lists files with this many bytes more than stored (a server that lost part of an upload). */
    var sizeSkew = 0L

    private var last = ""
    override val lastUrl: String get() = last

    init { existing.forEach { collections += WebDavPaths.pathKey(it) } }

    private fun key(url: String) = WebDavPaths.pathKey(url)

    private fun parentExists(path: String): Boolean {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        return parent == "/" || parent in collections
    }

    override fun propfind(url: String, depth: Int): List<WebDavXml.Entry> {
        requests += "PROPFIND $url"
        last = url
        val path = key(url)
        failing[path]?.let { throw DavException(it) }
        val self = when {
            path in collections -> WebDavXml.Entry(url, path, path.substringAfterLast('/'), true)
            path in files -> entryOf(path)
            else -> throw DavException(DavProblem.NOT_FOUND, 404)
        }
        if (depth == 0 || !self.isCollection) return listOf(self)
        val prefix = "$path/"
        val members = collections.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { WebDavXml.Entry(it, it, it.substringAfterLast('/'), true) } +
            files.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }.map(::entryOf)
        return listOf(self) + members
    }

    private fun entryOf(path: String): WebDavXml.Entry {
        val (bytes, etag) = files.getValue(path)
        return WebDavXml.Entry(path, path, path.substringAfterLast('/'), false, bytes.size + sizeSkew, 0L, etag)
    }

    override fun list(url: String): List<WebDavXml.Entry> = try {
        WebDavXml.membersOf(propfind(url, 1), url)
    } catch (e: DavException) {
        if (e.problem == DavProblem.NOT_FOUND) emptyList() else throw e
    }

    override fun exists(url: String): Boolean = try {
        propfind(url, 0); true
    } catch (e: DavException) {
        if (e.problem == DavProblem.NOT_FOUND) false else throw e
    }

    override fun get(url: String, maxBytes: Long): DavFile? {
        requests += "GET $url"
        last = url
        val stored = files[key(url)]
        if (stored != null && stored.first.size > maxBytes) throw DavException(DavProblem.BAD_RESPONSE)
        val result = stored?.let { DavFile(it.first, if (hideEtags) null else it.second) }
        afterGet?.let { hook -> afterGet = null; hook(url) }
        return result
    }

    override fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String?, ifNoneMatch: Boolean): String? {
        requests += "PUT $url${if (ifMatch != null) " if-match $ifMatch" else ""}${if (ifNoneMatch) " if-none-match" else ""}"
        last = url
        failNextPut?.let { failNextPut = null; throw DavException(it, 507) }
        beforePut?.let { hook -> beforePut = null; hook(url) }
        val path = key(url)
        if (!parentExists(path)) throw DavException(DavProblem.CONFLICT, 409)
        val current = files[path]
        if (!ignoreConditions) {
            if (ifMatch != null && (refuseConditionalWrites || current?.second != ifMatch)) throw DavException(DavProblem.PRECONDITION, 412)
            if (ifNoneMatch && (refuseConditionalWrites || current != null)) throw DavException(DavProblem.PRECONDITION, 412)
        }
        val etag = "\"e${++etagCounter}\""
        files[path] = bytes to etag
        afterPut?.let { hook -> afterPut = null; hook(url) }
        return if (hideEtags) null else etag
    }

    /** Another client writing the file directly. */
    fun write(url: String, text: String) {
        files[key(url)] = text.toByteArray() to "\"e${++etagCounter}\""
    }

    fun text(url: String): String? = files[key(url)]?.first?.toString(Charsets.UTF_8)

    override fun ensureCollection(url: String, root: String): Boolean {
        val path = key(url)
        if (path in collections) return false
        requests += "MKCOL $url"
        var created = false
        val parts = path.trim('/').split('/')
        for (i in parts.indices) {
            val p = "/" + parts.subList(0, i + 1).joinToString("/")
            if (p !in collections) { collections += p; created = true }
        }
        return created
    }

    override fun move(from: String, to: String): Boolean {
        requests += "MOVE $from"
        failNextMove?.let { failNextMove = null; throw DavException(it, 500) }
        if (moveUnsupported) return false
        val src = files.remove(key(from)) ?: throw DavException(DavProblem.NOT_FOUND, 404)
        files[key(to)] = src
        return true
    }

    override fun delete(url: String) {
        requests += "DELETE $url"
        val path = key(url)
        files.remove(path)
        collections.removeIf { it == path || it.startsWith("$path/") }
    }
}

/** A device's library for [DeviceSyncEngine] tests. */
class FakeLocal(var library: SyncLibrary = SyncLibrary.EMPTY) : DeviceSyncEngine.Local {
    var base: DeviceSyncJson.Base? = null
    var applied = 0
    var failApply = false

    override suspend fun snapshot(): SyncLibrary = library
    override suspend fun apply(diff: DeviceSyncMerge.Diff) {
        if (failApply) throw IllegalStateException("disk full")
        applied++
        library = DeviceSyncMerge.applyTo(library, diff)
    }
    override suspend fun readBase(): DeviceSyncJson.Base? = base
    override suspend fun writeBase(base: DeviceSyncJson.Base) { this.base = base }
}
