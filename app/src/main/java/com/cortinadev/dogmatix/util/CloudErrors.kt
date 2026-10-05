package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.BackupService

/** Nothing to connect to: no server address is set. */
class CloudNotConfiguredException : IllegalStateException("No WebDAV server is set up")

/** A cloud backup needs a passphrase and none (or a too short one) is set on this device. */
class CloudNoPassphraseException : IllegalStateException("No backup passphrase is set")

/** The backup is larger than a restore accepts ([limit] bytes), so uploading it would only produce a file that cannot be restored. */
class CloudBackupTooLargeException(val size: Long, val limit: Long) : IllegalStateException("Backup of $size bytes exceeds the restore limit of $limit")

/**
 * Failures of the WebDAV cloud as short codes. The last error of a backup, test or sync is stored
 * on the device as a code, not as a sentence, so it is shown in the language the app has when it is
 * *shown* (a background job runs with the system language on older Android versions); the screen
 * turns a [Decoded] into text (`CloudMessages.render`). Codes never carry a secret: a [DavException]
 * only has addresses and HTTP statuses. Pure JVM for the tests.
 */
object CloudErrors {

    /** Problems that are not a WebDAV status. */
    enum class Kind {
        WRONG_PASSPHRASE, NOT_A_BACKUP, INVALID_BACKUP, NEWER_BACKUP, SYNC_NEWER, SYNC_UNREADABLE, NOT_CONFIGURED, NO_PASSPHRASE, BACKUP_TOO_LARGE
    }

    sealed class Decoded {
        data class Dav(val problem: DavProblem, val code: Int, val detail: String?) : Decoded()
        data class Known(val kind: Kind) : Decoded()
        /** Anything else: the exception's own text (cut short). */
        data class Other(val text: String) : Decoded()
    }

    private const val SEP = '|'

    fun encode(error: Throwable): String = when (error) {
        is DavException -> "dav$SEP${error.problem.name}$SEP${error.code}$SEP${error.detail.orEmpty().replace(SEP, ' ')}"
        is BackupCrypto.UnreadableException -> "k${SEP}${Kind.WRONG_PASSPHRASE.name}"
        is BackupCrypto.NotEncryptedException -> "k${SEP}${Kind.NOT_A_BACKUP.name}"
        is BackupService.InvalidBackupException -> "k${SEP}${Kind.INVALID_BACKUP.name}"
        is BackupService.NewerBackupException -> "k${SEP}${Kind.NEWER_BACKUP.name}"
        is DeviceSyncEngine.NewerFileException -> "k${SEP}${Kind.SYNC_NEWER.name}"
        is DeviceSyncEngine.UnreadableFileException -> "k${SEP}${Kind.SYNC_UNREADABLE.name}"
        is CloudNotConfiguredException -> "k${SEP}${Kind.NOT_CONFIGURED.name}"
        is CloudNoPassphraseException -> "k${SEP}${Kind.NO_PASSPHRASE.name}"
        is CloudBackupTooLargeException -> "k${SEP}${Kind.BACKUP_TOO_LARGE.name}"
        else -> "other$SEP${(error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(120)}"
    }

    /** The error behind a stored code; null for an empty one (no error). */
    fun decode(text: String): Decoded? {
        if (text.isBlank()) return null
        val parts = text.split(SEP, limit = 4)
        return when (parts[0]) {
            "dav" -> {
                val problem = parts.getOrNull(1)?.let { n -> DavProblem.entries.firstOrNull { it.name == n } }
                if (problem == null) Decoded.Other(text.take(120))
                else Decoded.Dav(problem, parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(3)?.takeIf { it.isNotBlank() })
            }
            "k" -> parts.getOrNull(1)?.let { n -> Kind.entries.firstOrNull { it.name == n } }?.let { Decoded.Known(it) }
                ?: Decoded.Other(text.take(120))
            "other" -> Decoded.Other(parts.drop(1).joinToString(SEP.toString()).take(120))
            // A text stored by an earlier build: shown as it is.
            else -> Decoded.Other(text.take(120))
        }
    }

    /** Problems that say the connection settings no longer work (the connection test is then stale). */
    fun isConnectionProblem(error: Throwable): Boolean =
        error is DavException && error.problem in CONNECTION_PROBLEMS

    /** Passing problems (offline, slow server) that a background run only logs. */
    fun isTransient(error: Throwable): Boolean =
        error is DavException && (error.problem == DavProblem.NETWORK || error.problem == DavProblem.TIMEOUT)

    private val CONNECTION_PROBLEMS = setOf(
        DavProblem.AUTH, DavProblem.DIGEST_ONLY, DavProblem.NOT_WEBDAV, DavProblem.TLS, DavProblem.REDIRECT, DavProblem.BAD_URL
    )
}
