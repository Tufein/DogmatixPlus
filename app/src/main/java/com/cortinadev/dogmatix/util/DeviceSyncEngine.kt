package com.cortinadev.dogmatix.util

/**
 * One device sync against the shared `<root>/sync/library.json` on a [DavStore]:
 *
 * 1. read this device's library ([Local.snapshot]) and the base of its last sync;
 * 2. read the server file and its ETag (none yet = start it);
 * 3. merge three-way ([DeviceSyncMerge]); hold back when it would remove an unusual amount;
 * 4. when the server's copy changes, write it **only if it is still the version just read**
 *    (`If-Match`, or `If-None-Match: *` for a new file). If another device wrote in between
 *    (412), read and merge again. A server whose ETags never match (some proxies) gets one
 *    unconditional write once a second read shows nobody else changed the file;
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
        data class Synced(val added: Int, val removed: Int, val sent: Boolean) : Outcome()
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
        val storedBase = local.readBase()?.takeIf { it.remoteUrl == fileUrl }
        val snapshot = local.snapshot()
        if (onlyIfLocalChanges && storedBase != null && !DeviceSyncMerge.hasLocalChanges(storedBase.library, snapshot)) {
            return Outcome.NothingToSend
        }
        var rejected = 0
        // (was there a file, its ETag) when the server first refused the write.
        var firstRejected: Pair<Boolean, String?>? = null
        while (true) {
            val file = store.get(fileUrl, MAX_FILE_BYTES)
            val document = file?.let { readDocument(it.bytes) }
            // Without a server file the base says nothing about what the others removed.
            val base = storedBase?.library?.takeIf { document != null }
            val result = DeviceSyncMerge.merge(base, snapshot, document?.library ?: SyncLibrary.EMPTY)
            if (!allowMassRemoval && DeviceSyncMerge.tooManyRemovals(result, base)) return Outcome.HeldBack(result.removals)

            var sent = false
            if (document == null || result.remoteChanged) {
                val now = clock()
                val text = DeviceSyncJson.write(
                    DeviceSyncJson.Document(
                        library = result.merged,
                        updatedAt = now,
                        updatedBy = device.name,
                        devices = document?.devices.orEmpty() + (device.id to DeviceSyncJson.Device(device.name, now))
                    )
                ).toByteArray(Charsets.UTF_8)
                if (file == null) store.ensureCollection(folderUrl, WebDavPaths.normalizeServer(serverUrl) ?: folderUrl)
                try {
                    store.put(fileUrl, text, CONTENT_TYPE, ifMatch = file?.etag, ifNoneMatch = file == null)
                } catch (e: DavException) {
                    if (e.problem != DavProblem.PRECONDITION) throw e
                    rejected++
                    val seen = (file != null) to file?.etag
                    when {
                        // Someone wrote in between: read and merge again.
                        rejected == 1 -> { firstRejected = seen; continue }
                        // Nobody changed it, yet the server refuses its own ETag again: its
                        // conditional writes are broken, so write without a condition.
                        rejected == 2 && seen == firstRejected ->
                            store.put(fileUrl, text, CONTENT_TYPE, ifMatch = null, ifNoneMatch = false)
                        else -> throw e
                    }
                }
                sent = true
            }
            if (!result.toLocal.isEmpty) local.apply(result.toLocal)
            local.writeBase(DeviceSyncJson.Base(fileUrl, result.merged, clock()))
            return Outcome.Synced(result.toLocal.additions, result.toLocal.removals, sent)
        }
    }

    private fun readDocument(bytes: ByteArray): DeviceSyncJson.Document =
        when (val parsed = DeviceSyncJson.read(bytes.toString(Charsets.UTF_8))) {
            is DeviceSyncJson.Parsed.Ok -> parsed.document
            is DeviceSyncJson.Parsed.Newer -> throw NewerFileException(parsed.version)
            DeviceSyncJson.Parsed.Invalid -> throw UnreadableFileException()
        }

    companion object {
        const val FILE_NAME = "library.json"
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
