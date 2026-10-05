package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.CloudErrors
import com.cortinadev.dogmatix.util.DavException
import com.cortinadev.dogmatix.util.DavProblem
import com.cortinadev.dogmatix.util.WebDavPaths

/**
 * The message the user sees for a failure of the WebDAV cloud (connection test, backup, restore,
 * device sync). Never contains a secret: [DavException.detail] only carries addresses.
 *
 * What is stored on the device is the code from [CloudErrors.encode]; [render] turns it into text
 * with the context of the screen that shows it, so it has the in-app language.
 */
object CloudMessages {

    fun of(context: Context, error: Throwable): String = render(context, CloudErrors.decode(CloudErrors.encode(error)))

    /** The text for a stored error code (see [CloudErrors.encode]); "" for no error. */
    fun render(context: Context, code: String): String = render(context, CloudErrors.decode(code))

    fun render(context: Context, error: CloudErrors.Decoded?): String = when (error) {
        null -> ""
        is CloudErrors.Decoded.Dav -> forProblem(context, error.problem, error.code, error.detail)
        is CloudErrors.Decoded.Known -> when (error.kind) {
            CloudErrors.Kind.WRONG_PASSPHRASE -> context.getString(R.string.dav_err_passphrase)
            CloudErrors.Kind.NOT_A_BACKUP -> context.getString(R.string.dav_err_not_backup)
            CloudErrors.Kind.INVALID_BACKUP -> context.getString(R.string.backup_invalid)
            CloudErrors.Kind.NEWER_BACKUP -> context.getString(R.string.backup_newer_version)
            CloudErrors.Kind.SYNC_NEWER -> context.getString(R.string.dav_err_sync_newer)
            CloudErrors.Kind.SYNC_UNREADABLE -> context.getString(R.string.dav_err_sync_unreadable)
            CloudErrors.Kind.NOT_CONFIGURED -> context.getString(R.string.dav_err_not_configured)
            CloudErrors.Kind.NO_PASSPHRASE -> context.getString(R.string.dav_err_no_passphrase)
        }
        is CloudErrors.Decoded.Other -> context.getString(R.string.dav_err_other, error.text)
    }

    /** Problems that say the connection settings no longer work (the connection test is then stale). */
    fun isConnectionProblem(error: Throwable): Boolean = CloudErrors.isConnectionProblem(error)

    /** Passing problems (offline, slow server) that a background run only logs. */
    fun isTransient(error: Throwable): Boolean = CloudErrors.isTransient(error)

    private fun forProblem(context: Context, problem: DavProblem, code: Int, detail: String?): String = when (problem) {
        DavProblem.AUTH -> context.getString(R.string.dav_err_auth)
        DavProblem.DIGEST_ONLY -> context.getString(R.string.dav_err_digest)
        DavProblem.FORBIDDEN -> context.getString(R.string.dav_err_forbidden)
        DavProblem.NOT_FOUND -> context.getString(R.string.dav_err_not_found)
        DavProblem.NOT_WEBDAV -> context.getString(R.string.dav_err_not_webdav)
        DavProblem.CONFLICT -> context.getString(R.string.dav_err_conflict)
        DavProblem.PRECONDITION -> context.getString(R.string.dav_err_precondition)
        DavProblem.TOO_LARGE -> context.getString(R.string.dav_err_too_large)
        DavProblem.LOCKED -> context.getString(R.string.dav_err_locked)
        DavProblem.NO_SPACE -> context.getString(R.string.dav_err_no_space)
        DavProblem.SERVER -> context.getString(R.string.dav_err_server, code)
        DavProblem.HTTP -> context.getString(R.string.dav_err_http, code)
        DavProblem.REDIRECT -> context.getString(R.string.dav_err_redirect, detail?.let { WebDavPaths.hostOf(it) }?.takeIf { it.isNotBlank() } ?: "?")
        DavProblem.NETWORK -> context.getString(R.string.dav_err_network)
        DavProblem.TIMEOUT -> context.getString(R.string.dav_err_timeout)
        DavProblem.TLS -> context.getString(R.string.dav_err_tls)
        DavProblem.BAD_URL -> context.getString(R.string.dav_err_bad_url)
        DavProblem.BAD_RESPONSE -> context.getString(R.string.dav_err_bad_response)
    }
}
