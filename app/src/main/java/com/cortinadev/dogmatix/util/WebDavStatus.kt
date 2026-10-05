package com.cortinadev.dogmatix.util

import java.io.IOException

/** What went wrong talking to a WebDAV server, in terms the user can act on. */
enum class DavProblem {
    /** 401: wrong user name or (app) password. */
    AUTH,
    /** 401 and the server offers only Digest login, which the app does not speak. */
    DIGEST_ONLY,
    /** 403: logged in, but not allowed here (read-only share, wrong user's folder…). */
    FORBIDDEN,
    /** 404: the address or folder does not exist. */
    NOT_FOUND,
    /** 405 / 501, or an answer that is not a WebDAV multistatus (a web page): not a WebDAV address. */
    NOT_WEBDAV,
    /** 409: a parent folder is missing. */
    CONFLICT,
    /** 412: the file changed on the server since it was read (another device wrote it). */
    PRECONDITION,
    /** 413: the server refuses files this large. */
    TOO_LARGE,
    /** 423: the file is locked by another client. */
    LOCKED,
    /** 507: the account or disk is full. */
    NO_SPACE,
    /** 5xx other than 501/507. */
    SERVER,
    /** Any other unexpected status. */
    HTTP,
    /** A redirect the client does not follow (another host, or a loop). */
    REDIRECT,
    /** No connection: unknown host, refused, no route, connection reset. */
    NETWORK,
    /** The server took too long. */
    TIMEOUT,
    /** TLS handshake failed: the certificate is not trusted or does not match. */
    TLS,
    /** The address cannot be a URL. */
    BAD_URL,
    /** The answer was readable as HTTP but its content was not what was asked for (too big, garbled). */
    BAD_RESPONSE
}

/** A WebDAV call failed; [code] is the HTTP status when there was one (0 otherwise). */
class DavException(
    val problem: DavProblem,
    val code: Int = 0,
    /** Extra information for the message: the redirect target, the low-level error. Never a secret. */
    val detail: String? = null
) : IOException("WebDAV ${problem.name.lowercase()}${if (code > 0) " ($code)" else ""}${detail?.let { ": $it" } ?: ""}")

/** Maps HTTP answers of WebDAV servers to a [DavProblem]. Pure JVM for the tests. */
object WebDavStatus {

    /** Statuses after which the next address candidate cannot do better (see [WebDavPaths.candidates]). */
    val FINAL: Set<DavProblem> = setOf(DavProblem.AUTH, DavProblem.DIGEST_ONLY, DavProblem.NETWORK, DavProblem.TIMEOUT, DavProblem.TLS, DavProblem.BAD_URL)

    /**
     * The problem behind a non-success [code]. [wwwAuthenticate] are the `WWW-Authenticate` header
     * values of a 401: a server that offers Digest but not Basic gets [DavProblem.DIGEST_ONLY].
     */
    fun classify(code: Int, wwwAuthenticate: List<String> = emptyList()): DavProblem = when (code) {
        401 -> {
            val schemes = wwwAuthenticate.map { it.trim().substringBefore(' ').lowercase() }.filter { it.isNotEmpty() }
            if (schemes.isNotEmpty() && "basic" !in schemes && "digest" in schemes) DavProblem.DIGEST_ONLY else DavProblem.AUTH
        }
        403 -> DavProblem.FORBIDDEN
        404, 410 -> DavProblem.NOT_FOUND
        405, 501 -> DavProblem.NOT_WEBDAV
        409 -> DavProblem.CONFLICT
        412 -> DavProblem.PRECONDITION
        413 -> DavProblem.TOO_LARGE
        423 -> DavProblem.LOCKED
        507 -> DavProblem.NO_SPACE
        in 300..399 -> DavProblem.REDIRECT
        in 500..599 -> DavProblem.SERVER
        else -> DavProblem.HTTP
    }

    /**
     * Of the failures of several address candidates, the one to tell the user about: a login
     * problem says more than "not found" on a guessed address; otherwise the first one counts.
     */
    fun mostRelevant(failures: List<DavException>): DavException? {
        val rank = listOf(DavProblem.AUTH, DavProblem.DIGEST_ONLY, DavProblem.FORBIDDEN, DavProblem.TLS, DavProblem.NO_SPACE)
        return rank.firstNotNullOfOrNull { p -> failures.firstOrNull { it.problem == p } } ?: failures.firstOrNull()
    }

    /** What a MKCOL answer means. */
    enum class MkcolAnswer {
        /** 200 / 201 / 204: the folder was made. */
        CREATED,
        /** 405: "already exists" on most servers, but also what a server that refuses MKCOL says; the caller checks. */
        NOT_ALLOWED,
        /** Anything else is a failure ([classify]). */
        FAILED
    }

    fun mkcolAnswer(code: Int): MkcolAnswer = when (code) {
        200, 201, 204 -> MkcolAnswer.CREATED
        405 -> MkcolAnswer.NOT_ALLOWED
        else -> MkcolAnswer.FAILED
    }

    /** Strong ETag usable in `If-Match` (a weak `W/"…"` one never matches there); null otherwise. */
    fun strongEtag(etag: String?): String? {
        val e = etag?.trim().orEmpty()
        if (e.isEmpty() || e.startsWith("W/", ignoreCase = true)) return null
        return e
    }
}
