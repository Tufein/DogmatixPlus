package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.util.CertTrust
import com.cortinadev.dogmatix.util.DavException
import com.cortinadev.dogmatix.util.DavFile
import com.cortinadev.dogmatix.util.DavProblem
import com.cortinadev.dogmatix.util.DavStore
import com.cortinadev.dogmatix.util.WebDavPaths
import com.cortinadev.dogmatix.util.WebDavStatus
import com.cortinadev.dogmatix.util.WebDavXml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A small WebDAV client (Nextcloud, ownCloud, Synology, Koofr, Apache, nginx, rclone…): PROPFIND,
 * GET, PUT, MKCOL and DELETE with HTTP Basic login. OkHttp, because HttpURLConnection refuses the
 * WebDAV methods. TLS goes through the app's own trust ([TlsTrust], which also accepts the RomM
 * certificate the user confirmed) and, for a server with a self-signed or private-CA certificate, the
 * certificate fingerprint the user confirmed for *this* server ([trustedFingerprint]).
 *
 * Every call is **blocking**: run it on `Dispatchers.IO`. Failures are [DavException]s with a
 * [DavProblem] the UI turns into a clear message. Redirects are followed by hand and only on the
 * same host (http → https allowed), so the login never goes to another server and a PUT is never
 * turned into a GET; [lastUrl] tells where a request really ended.
 */
class WebDavClient(
    user: String,
    password: String,
    /** SHA-256 of a server certificate to accept besides the ones Android trusts; empty = none. */
    trustedFingerprint: String = "",
    private val http: OkHttpClient = clientFor(trustedFingerprint)
) : DavStore {
    /** Preemptive Basic login (WebDAV servers expect it on every request); none without a user. */
    private val authorization: String? =
        if (user.isBlank()) null else Credentials.basic(user.trim(), password, Charsets.UTF_8)

    /** Where the last request ended after redirects (to adopt a corrected address). */
    @Volatile private var finalUrl: String = ""
    override val lastUrl: String get() = finalUrl

    /** Lists [url]: `Depth: 0` = only the resource itself, `1` = also its members. */
    override fun propfind(url: String, depth: Int): List<WebDavXml.Entry> {
        val body = WebDavXml.PROPFIND_BODY.toRequestBody(XML)
        execute("PROPFIND", url, body, mapOf("Depth" to depth.toString())).use { response ->
            when (response.code) {
                207 -> {
                    val text = readCapped(response, MAX_LISTING_BYTES).toString(Charsets.UTF_8)
                    return try {
                        WebDavXml.parse(text)
                    } catch (e: WebDavXml.NotMultistatusException) {
                        throw DavException(DavProblem.NOT_WEBDAV, 207)
                    }
                }
                // A plain web server answers PROPFIND like a GET: a page, not a listing.
                in 200..299 -> throw DavException(DavProblem.NOT_WEBDAV, response.code)
                else -> throw failure(response)
            }
        }
    }

    /** The members of the collection at [url] (itself left out); empty when it does not exist. */
    override fun list(url: String): List<WebDavXml.Entry> = try {
        WebDavXml.membersOf(propfind(url, 1), url)
    } catch (e: DavException) {
        if (e.problem == DavProblem.NOT_FOUND) emptyList() else throw e
    }

    /** Whether [url] exists (any other failure is thrown). */
    override fun exists(url: String): Boolean = try {
        propfind(url, 0)
        true
    } catch (e: DavException) {
        if (e.problem == DavProblem.NOT_FOUND) false else throw e
    }

    /** The file at [url], or null when there is none. Larger than [maxBytes] → [DavProblem.BAD_RESPONSE]. */
    override fun get(url: String, maxBytes: Long): DavFile? {
        // identity: Apache changes the ETag of compressed answers ("…-gzip"), which then never matches If-Match.
        execute("GET", url, null, mapOf("Accept-Encoding" to "identity")).use { response ->
            return when (response.code) {
                200 -> DavFile(readCapped(response, maxBytes), WebDavStatus.strongEtag(response.header("ETag")))
                404, 410 -> null
                else -> throw failure(response)
            }
        }
    }

    /**
     * Writes [bytes] to [url]. With [ifMatch] the write only happens when the file still has that
     * ETag; with [ifNoneMatch] only when there is no file yet. Both fail with
     * [DavProblem.PRECONDITION] otherwise. Returns the new ETag when the server tells it.
     */
    override fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String?, ifNoneMatch: Boolean): String? {
        val headers = buildMap {
            ifMatch?.let { put("If-Match", it) }
            if (ifNoneMatch) put("If-None-Match", "*")
        }
        execute("PUT", url, bytes.toRequestBody(contentType.toMediaType()), headers).use { response ->
            if (response.code in 200..299) return WebDavStatus.strongEtag(response.header("ETag"))
            throw failure(response)
        }
    }

    /**
     * Creates the collection [url]; false when the server says it already exists (405). 409
     * (parent missing) is thrown. Whether a 405 really means "exists" is for [ensureCollection] to check.
     */
    fun mkcol(url: String): Boolean {
        execute("MKCOL", url, EMPTY_BODY, emptyMap()).use { response ->
            return when (WebDavStatus.mkcolAnswer(response.code)) {
                WebDavStatus.MkcolAnswer.CREATED -> true
                WebDavStatus.MkcolAnswer.NOT_ALLOWED -> false
                WebDavStatus.MkcolAnswer.FAILED -> throw failure(response)
            }
        }
    }

    /** MOVE [from] to [to], replacing it; false when the server does not do MOVE (405, 501, 403 on the verb). */
    override fun move(from: String, to: String): Boolean {
        val headers = mapOf("Destination" to to, "Overwrite" to "T")
        execute("MOVE", from, EMPTY_BODY, headers).use { response ->
            return when {
                response.code in 200..299 -> true
                response.code == 405 || response.code == 501 || response.code == 403 -> false
                else -> throw failure(response)
            }
        }
    }

    /**
     * Makes sure the collection [url] exists, creating it and its missing parents below [root]
     * (which must exist). Returns true when something was created.
     */
    override fun ensureCollection(url: String, root: String): Boolean {
        if (exists(url)) return false
        return create(url, root, 0)
    }

    private fun create(url: String, root: String, depth: Int): Boolean {
        try {
            return mkcolChecked(url)
        } catch (e: DavException) {
            // 409 = a parent is missing (some servers say 404): create the parent first, then retry.
            if (e.problem != DavProblem.CONFLICT && e.problem != DavProblem.NOT_FOUND) throw e
            val parent = WebDavPaths.parentOf(url)
            if (parent == null || parent.length < root.length || depth >= MAX_DEPTH) throw e
            create(parent, root, depth + 1)
            return mkcolChecked(url)
        }
    }

    /**
     * [mkcol], where a 405 only counts as "exists" when the folder really is there now: a server
     * that refuses MKCOL for a folder PROPFIND found missing is refusing to create it (read-only
     * share, wrong place), which must fail here instead of at the first upload.
     */
    private fun mkcolChecked(url: String): Boolean {
        if (mkcol(url)) return true
        if (exists(url)) return false
        throw DavException(DavProblem.FORBIDDEN, 405, "the server will not create the folder")
    }

    /** Deletes [url]; a file that is already gone counts as deleted. */
    override fun delete(url: String) {
        execute("DELETE", url, EMPTY_BODY, emptyMap()).use { response ->
            if (response.code in 200..299 || response.code == 404 || response.code == 410) return
            throw failure(response)
        }
    }

    /** Sends one request, following same-host redirects; every I/O failure becomes a [DavException]. */
    private fun execute(verb: String, url: String, body: RequestBody?, headers: Map<String, String>): Response {
        var target = url
        for (hop in 0..MAX_REDIRECTS) {
            val request = try {
                val builder = Request.Builder().url(target).method(verb, body)
                    .header("User-Agent", "DogmatixPlus/${BuildConfig.VERSION_NAME}")
                authorization?.let { builder.header("Authorization", it) }
                headers.forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            } catch (e: IllegalArgumentException) {
                throw DavException(DavProblem.BAD_URL)
            }
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw problemOf(e)
            }
            if (response.code !in REDIRECTS) {
                finalUrl = response.request.url.toString()
                return response
            }
            val from = response.request.url
            val next = response.header("Location")?.let { from.resolve(it) }
            response.close()
            if (next == null || !sameServer(from, next)) throw DavException(DavProblem.REDIRECT, response.code, next?.toString())
            target = next.toString()
        }
        throw DavException(DavProblem.REDIRECT, 0, target)
    }

    private fun sameServer(from: HttpUrl, to: HttpUrl): Boolean {
        if (!from.host.equals(to.host, ignoreCase = true)) return false
        if (from.scheme == to.scheme) return from.port == to.port
        // Upgrading to https on the same host is fine; going down to http is not.
        return from.scheme == "http" && to.scheme == "https"
    }

    private fun failure(response: Response): DavException =
        DavException(WebDavStatus.classify(response.code, response.headers("WWW-Authenticate")), response.code)

    private fun readCapped(response: Response, max: Long): ByteArray {
        val body = response.body ?: return ByteArray(0)
        if (body.contentLength() > max) throw DavException(DavProblem.BAD_RESPONSE, response.code, "too large")
        val out = ByteArrayOutputStream()
        try {
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > max) throw DavException(DavProblem.BAD_RESPONSE, response.code, "too large")
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: DavException) {
            throw e
        } catch (e: IOException) {
            throw problemOf(e)
        }
        return out.toByteArray()
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        /** MKCOL and DELETE with `Content-Length: 0`: a few servers (nginx, lighttpd) answer 411 without it. */
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 4
        private const val MAX_DEPTH = 8
        private const val MAX_LISTING_BYTES = 8L * 1024 * 1024

        /**
         * One client for all WebDAV calls (connection pool shared between calls). Redirects are
         * followed by [execute] itself; the RomM trust pin applies like everywhere else.
         */
        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(TlsTrust.liveSocketFactory, TlsTrust.liveTrustManager)
                .hostnameVerifier(TlsTrust.liveHostnameVerifier)
                .build()
        }

        /**
         * [sharedHttp], or (with a valid [fingerprint]) a copy of it that also accepts the one server
         * certificate with exactly that SHA-256 fingerprint, even when its name does not match. The
         * copy shares the connection pool; it is only used for the server the user confirmed it for.
         */
        fun clientFor(fingerprint: String): OkHttpClient {
            if (!CertTrust.isValid(fingerprint)) return sharedHttp
            val trust = PinnedLeafTrust(fingerprint, TlsTrust.liveTrustManager)
            val factory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }.socketFactory
            val fallback = TlsTrust.liveHostnameVerifier
            val verifier = HostnameVerifier { host, session ->
                val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
                (leaf != null && CertTrust.matches(fingerprint, CertTrust.sha256Hex(leaf.encoded))) || fallback.verify(host, session)
            }
            return sharedHttp.newBuilder().sslSocketFactory(factory, trust).hostnameVerifier(verifier).build()
        }

        /** The [DavProblem] behind a low-level I/O failure. */
        fun problemOf(e: IOException): DavException = when (e) {
            is DavException -> e
            // Handshake, untrusted certificate, wrong protocol (https to a plain-http port): all "the secure connection failed".
            is SSLException -> DavException(DavProblem.TLS, 0, e.javaClass.simpleName)
            is SocketTimeoutException -> DavException(DavProblem.TIMEOUT)
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> DavException(DavProblem.NETWORK, 0, e.javaClass.simpleName)
            is InterruptedIOException -> DavException(DavProblem.TIMEOUT)
            else -> DavException(DavProblem.NETWORK, 0, e.javaClass.simpleName)
        }
    }

    /** Accepts the leaf certificate with the confirmed fingerprint; anything else goes to [fallback]. */
    private class PinnedLeafTrust(private val fingerprint: String, private val fallback: X509TrustManager) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = fallback.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            val leaf = chain.firstOrNull()
            if (leaf != null && CertTrust.matches(fingerprint, CertTrust.sha256Hex(leaf.encoded))) return
            fallback.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = fallback.acceptedIssuers
    }
}
