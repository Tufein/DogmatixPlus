package com.cortinadev.dogmatix.util

import java.security.MessageDigest
import java.util.UUID

/**
 * One device sync against the shared `<root>/sync/library.json` on a [DavStore]:
 *
 * 1. read this device's library ([Local.snapshot]) and the base of its last sync;
 * 2. read the server file and its ETag (none yet = start it);
 * 3. merge three-way ([DeviceSyncMerge]); hold back when it would remove an unusual amount;
 * 4. when the server's copy changes, write it **only if it is still the version just read**
 *    ([DavConditionalWriter]: `If-Match` / `If-None-Match: *`; where the server cannot enforce
 *    that, the file is read again before and after the write and the merge repeated when it
 *    differs, so another device's change is never silently overwritten);
 *    a server copy older than this device's last sync (a restored snapshot) is merged as a union,
 *    and a base made with another login or server is ignored;
 * 5. only then change this device ([Local.apply], one transaction) and store the new base.
 *
 * So a failed read or write leaves this device untouched, and a server file that is missing,
 * damaged or from a newer app never makes local things disappear: a missing file is a fresh
 * start (union), the other two stop the sync without writing anything. Pure JVM for the tests.
 */
class DeviceSyncEngine(
    private val store: DavStore,
    /** The server address the folder lives under (where creating folders stops). */
    private val serverUrl: String,
    rootUrl: String,
    private val local: Local,
    /** Identifies the login (see [accountKey]); the base of another login is not trusted. */
    private val account: String = "",
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** This device's side: its library, how to change it, and where the base is kept. */
    interface Local {
        suspend fun snapshot(): SyncLibrary
        /** Applies [diff] to the device in one go (all or nothing). */
        suspend fun apply(diff: DeviceSyncMerge.Diff)
        suspend fun readBase(): DeviceSyncJson.Base?
        suspend fun writeBase(base: DeviceSyncJson.Base)
    }

    data class Device(val id: String, val name: String)

    sealed class Outcome {
        /** [added] / [removed] things on this device; [sent] = the server's copy was updated. */
        data class Synced(val added: Int, val removed: Int, val sent: Boolean, val rolledBack: Boolean = false) : Outcome()
        /** Nothing done: [removals] things would go at once; ask the user, then sync with `allowMassRemoval`. */
        data class HeldBack(val removals: Int) : Outcome()
        /** Asked to sync only local changes, and there were none. */
        object NothingToSend : Outcome()
    }

    /** The server file was written by a newer Dogmatix; it is left alone. */
    class NewerFileException(val version: Int) : IllegalStateException("Sync file format $version is newer than ${DeviceSyncJson.VERSION}")

    /** The server file is not a sync file (damaged, or something else under that name); it is left alone. */
    class UnreadableFileException : IllegalStateException("The sync file on the server cannot be read")

    private val folderUrl = WebDavPaths.childCollection(rootUrl, CloudBackupEngine.SYNC)
    /** `<root>/sync/library.json`; also the identity the base is stored with. */
    val fileUrl: String = WebDavPaths.child(folderUrl, FILE_NAME)

    suspend fun sync(device: Device, allowMassRemoval: Boolean = false, onlyIfLocalChanges: Boolean = false): Outcome {
        // A base of another server, folder or login says nothing about this file (an old base without a login is trusted).
        val storedBase = local.readBase()?.takeIf { it.remoteUrl == fileUrl && (it.account.isEmpty() || it.account == account) }
        val snapshot = local.snapshot()
        if (onlyIfLocalChanges && storedBase != null && !DeviceSyncMerge.hasLocalChanges(storedBase.library, snapshot)) {
            return Outcome.NothingToSend
        }
        val writer = DavConditionalWriter(store, fileUrl, CONTENT_TYPE, MAX_FILE_BYTES)
        while (true) {
            val file = store.get(fileUrl, MAX_FILE_BYTES)
            // An empty file (an upload that was cut off) is no sync file at all: it is replaced, nothing is lost.
            val document = file?.takeIf { it.bytes.isNotEmpty() }?.let { readDocument(it.bytes) }
            // A server that went back to an older copy (restored snapshot) says nothing about removals either.
            val rolledBack = document != null && storedBase != null && isRollback(document.updatedAt, storedBase.savedAt)
            // Without a server file the base says nothing about what the others removed.
            val base = storedBase?.library?.takeIf { document != null && !rolledBack }
            val result = DeviceSyncMerge.merge(base, snapshot, document?.library ?: SyncLibrary.EMPTY)
            if (!allowMassRemoval && DeviceSyncMerge.tooManyRemovals(result, base)) return Outcome.HeldBack(result.removals)

            var sent = false
            if (document == null || result.remoteChanged) {
                val now = clock()
                val rev = nonce()
                val text = DeviceSyncJson.write(
                    DeviceSyncJson.Document(
                        library = result.merged,
                        updatedAt = now,
                        updatedBy = device.name,
                        devices = document?.devices.orEmpty() + (device.id to DeviceSyncJson.Device(device.name, now)),
                        rev = rev
                    )
                ).toByteArray(Charsets.UTF_8)
                if (file == null) store.ensureCollection(folderUrl, WebDavPaths.normalizeServer(serverUrl) ?: folderUrl)
                if (!writer.attempt(file, text) { stored -> isOurs(stored, text, rev) }) continue
                sent = true
            }
            if (!result.toLocal.isEmpty) local.apply(result.toLocal)
            local.writeBase(DeviceSyncJson.Base(fileUrl, result.merged, clock(), account))
            return Outcome.Synced(result.toLocal.additions, result.toLocal.removals, sent, rolledBack)
        }
    }

    private fun readDocument(bytes: ByteArray): DeviceSyncJson.Document =
        when (val parsed = DeviceSyncJson.read(bytes.toString(Charsets.UTF_8))) {
            is DeviceSyncJson.Parsed.Ok -> parsed.document
            is DeviceSyncJson.Parsed.Newer -> throw NewerFileException(parsed.version)
            DeviceSyncJson.Parsed.Invalid -> throw UnreadableFileException()
        }

    companion object {
        /** A copy this much older than this device's last sync means the server went back in time. */
        const val ROLLBACK_SLACK_MS = 5L * 60_000

        /** The server file's [updatedAt] is older than the base's [savedAt] by more than the clock slack between handhelds. */
        fun isRollback(updatedAt: Long, savedAt: Long): Boolean = savedAt > 0 && updatedAt + ROLLBACK_SLACK_MS < savedAt

        /** Short hash of the user name: part of the base's identity, so a new login never reuses the old one's base. */
        fun accountKey(user: String): String =
            MessageDigest.getInstance("SHA-256").digest(user.trim().lowercase().toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it) }

        /** [stored] (read back from the server) is the write of [written] (same bytes, or carries its [rev] mark). */
        fun isOurs(stored: ByteArray, written: ByteArray, rev: String): Boolean {
            if (stored.contentEquals(written)) return true
            val parsed = DeviceSyncJson.read(stored.toString(Charsets.UTF_8))
            return parsed is DeviceSyncJson.Parsed.Ok && rev.isNotEmpty() && parsed.document.rev == rev
        }

        const val FILE_NAME = "library.json"
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
