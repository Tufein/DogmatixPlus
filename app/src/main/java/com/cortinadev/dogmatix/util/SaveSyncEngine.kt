package com.cortinadev.dogmatix.util

import java.io.IOException

/** Both sides changed since the last sync; the user picks which one stays. */
data class SaveConflict(val local: LocalSaveFile, val remote: RemoteSaveFile)

data class SaveSyncResult(
    val finishedAt: Long,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val unchanged: Int = 0,
    val conflicts: Int = 0,
    /** Device files RomM has no game for (or several games, and none could be picked). */
    val notMatched: Int = 0,
    val failed: Int = 0,
    /** The first few failures, as "file: reason". */
    val errors: List<String> = emptyList(),
    /** Saves removed on the device / on the server because they were deleted on the other side. */
    val deletedOnDevice: Int = 0,
    val deletedOnServer: Int = 0,
    /** Deletions that were not carried out: there were more than expected, the user has to confirm. */
    val deletionsHeld: Int = 0
)

/** The RomM side of a save sync (implemented over [com.cortinadev.dogmatix.data.service.RommClient]). */
interface SaveServer {
    suspend fun list(kind: SaveKind): List<RemoteSaveFile>
    suspend fun download(save: RemoteSaveFile): ByteArray
    /** Stores [bytes] as [fileName] for ROM [romId], replacing a save of that name; returns the stored save. */
    suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray): RemoteSaveFile?
    suspend fun searchRoms(term: String): List<SaveSyncPlanner.RomCandidate>
    /** Removes [save] from the server. */
    suspend fun delete(save: RemoteSaveFile)
}

/** The device side: the picked saves / states folders. */
interface SaveStore {
    data class Listing(
        val files: List<LocalSaveFile>,
        /** Folders directly inside each picked folder (emulator / core folders). */
        val topFolders: Map<SaveKind, Set<String>>,
        /** Files skipped for their size. */
        val tooLarge: Int = 0,
        /** Kinds listed only through emulators' own folders: server files of other emulators have no place here. */
        val noRootFolder: Set<SaveKind> = emptySet()
    )

    /** Kinds with a picked folder are the keys of [Listing.topFolders]. */
    suspend fun list(): Listing
    suspend fun read(file: LocalSaveFile): ByteArray
    /** Writes [bytes] to [path] (creating its folder) and returns the file as now on disk. */
    suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile
    /** Keeps a copy of [file] before a download replaces it. */
    suspend fun backup(file: LocalSaveFile)
    /** Removes [file] from the device (the caller has made a backup). */
    suspend fun delete(file: LocalSaveFile)
}

/**
 * Carries a save sync out: lists both sides, lets [SaveSyncPlanner] decide, uploads,
 * downloads and compares, and keeps [records] (what both sides looked like after the last
 * sync of each file) up to date. Pure Kotlin, so it is tested against fakes and a real server.
 */
class SaveSyncEngine(
    private val server: SaveServer,
    private val store: SaveStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** Whether deletions are mirrored (Settings → Save sync); read at the start of every sync. */
    private val syncDeletions: suspend () -> Boolean = { false }
) {
    /** Save files RomM had no (single) game for, with when it was asked: not asked again for a day. */
    private val notFoundCache = mutableMapOf<String, Long>()

    suspend fun sync(records: MutableMap<String, SaveSyncRecord>, confirmDeletions: Boolean = false): Pair<SaveSyncResult, List<SaveConflict>> {
        val listing = store.list()
        val remotes = SaveSyncPlanner.latestPerName(listing.topFolders.keys.flatMap { server.list(it) })
        val planned = SaveSyncPlanner.plan(listing.files, remotes, records.values.toList(), listing.topFolders, syncDeletions(), listing.noRootFolder)
        // A lot of deletions at once usually means something is wrong (a folder emptied, a wrong
        // server), not that the user cleaned up: hold them back until the user says yes.
        val deletions = planned.count { it is SaveSyncAction.DeleteRemote || it is SaveSyncAction.DeleteLocal }
        val held = !confirmDeletions && deletions > maxOf(MIN_DELETIONS_WITHOUT_ASKING, records.size / 4)
        val actions = if (held) planned.filterNot { it is SaveSyncAction.DeleteRemote || it is SaveSyncAction.DeleteLocal } else planned

        var uploaded = 0; var downloaded = 0; var unchanged = 0; var notMatched = listing.tooLarge; var failed = 0
        var deletedOnDevice = 0; var deletedOnServer = 0
        val conflicts = mutableListOf<SaveConflict>()
        val errors = mutableListOf<String>()
        val transfers = actions.count { it.isTransfer() }
        var done = 0

        for (action in actions) {
            if (action.isTransfer()) onProgress(++done, transfers)
            try {
                when (action) {
                    is SaveSyncAction.InSync -> unchanged++
                    is SaveSyncAction.Conflict -> conflicts += SaveConflict(action.local, action.remote)
                    is SaveSyncAction.Ambiguous, is SaveSyncAction.Blocked -> notMatched++
                    is SaveSyncAction.DeleteRemote -> {
                        server.delete(action.remote)
                        records.remove(SaveSyncPlanner.key(action.record.kind, action.record.path))
                        deletedOnServer++
                    }
                    is SaveSyncAction.DeleteLocal -> {
                        store.backup(action.local)
                        store.delete(action.local)
                        records.remove(SaveSyncPlanner.key(action.record.kind, action.record.path))
                        deletedOnDevice++
                    }
                    is SaveSyncAction.Download -> { download(action.remote, action.path, action.replacing, records); downloaded++ }
                    is SaveSyncAction.Upload -> {
                        val romId = action.romId ?: findRom(action.local)
                        if (romId == null) notMatched++ else { upload(action.local, romId, records); uploaded++ }
                    }
                    is SaveSyncAction.Compare -> {
                        val localBytes = store.read(action.local)
                        val same = action.remote.contentHash?.let { it.equals(md5(localBytes), ignoreCase = true) }
                            ?: server.download(action.remote).contentEquals(localBytes)
                        if (same) {
                            records[key(action.local)] = record(action.local, action.remote)
                            unchanged++
                        } else conflicts += SaveConflict(action.local, action.remote)
                    }
                }
            } catch (e: Exception) {
                failed++
                if (errors.size < 5) errors += "${action.fileName()}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // Records of files gone from both sides would only grow the file. Files this sync
        // downloaded are not in the listing, but their server copy is.
        val livePaths = listing.files.map(::key).toSet()
        val liveRemotes = remotes.map { it.kind to it.id }.toSet()
        records.entries.retainAll { (k, r) -> k in livePaths || (r.kind to r.remoteId) in liveRemotes }

        val result = SaveSyncResult(
            clock(), uploaded, downloaded, unchanged, conflicts.size, notMatched, failed, errors,
            deletedOnDevice, deletedOnServer, if (held) deletions else 0
        )
        return result to conflicts
    }

    /** Keeps the device copy (uploads it) or the server copy (downloads it) of [conflict]. */
    suspend fun resolve(conflict: SaveConflict, keepDevice: Boolean, records: MutableMap<String, SaveSyncRecord>) {
        val local = store.list().files.firstOrNull { it.kind == conflict.local.kind && it.path == conflict.local.path }
            ?: throw IOException("${conflict.local.name} is no longer on the device")
        if (keepDevice) upload(local, conflict.remote.romId, records)
        else download(conflict.remote, local.path, local, records)
    }

    private suspend fun upload(local: LocalSaveFile, romId: Int, records: MutableMap<String, SaveSyncRecord>) {
        val stored = server.upload(local.kind, romId, local.name, SaveSyncPlanner.emulatorFor(local), store.read(local))
            ?: server.list(local.kind).filter { it.romId == romId && it.fileName.equals(local.name, ignoreCase = true) }
                .maxByOrNull { SaveSyncPlanner.epochMillis(it.updatedAt) ?: Long.MIN_VALUE }
            ?: throw IOException("RomM did not confirm the upload")
        records[key(local)] = record(local, stored)
    }

    private suspend fun download(remote: RemoteSaveFile, path: String, replacing: LocalSaveFile?, records: MutableMap<String, SaveSyncRecord>) {
        val bytes = server.download(remote)
        if (replacing != null) store.backup(replacing)
        val written = store.write(remote.kind, path, bytes)
        records[key(written)] = record(written, remote)
    }

    private suspend fun findRom(local: LocalSaveFile): Int? {
        val cacheKey = key(local)
        val askedAt = notFoundCache[cacheKey]
        if (askedAt != null && clock() - askedAt < NOT_FOUND_RETRY_MS) return null
        for (term in SaveSyncPlanner.searchTerms(local.name)) {
            when (val match = SaveSyncPlanner.matchRom(local, server.searchRoms(term))) {
                is SaveSyncPlanner.RomMatch.Found -> return match.romId
                // The game is there more than once: another search will not make it single.
                SaveSyncPlanner.RomMatch.Ambiguous -> break
                SaveSyncPlanner.RomMatch.NotFound -> Unit
            }
        }
        notFoundCache[cacheKey] = clock()
        return null
    }

    private fun SaveSyncAction.isTransfer() =
        this is SaveSyncAction.Upload || this is SaveSyncAction.Download || this is SaveSyncAction.Compare

    private fun SaveSyncAction.fileName(): String = when (this) {
        is SaveSyncAction.Download -> remote.fileName
        is SaveSyncAction.Upload -> local.name
        is SaveSyncAction.Compare -> local.name
        is SaveSyncAction.Conflict -> local.name
        is SaveSyncAction.InSync -> local.name
        is SaveSyncAction.Ambiguous -> local.name
        is SaveSyncAction.Blocked -> remote.fileName
        is SaveSyncAction.DeleteRemote -> remote.fileName
        is SaveSyncAction.DeleteLocal -> local.name
    }

    companion object {
        private const val NOT_FOUND_RETRY_MS = 24 * 60 * 60 * 1000L
        /** Up to this many deletions (or a quarter of all synced files, if more) go through without asking. */
        private const val MIN_DELETIONS_WITHOUT_ASKING = 3

        fun key(local: LocalSaveFile) = SaveSyncPlanner.key(local.kind, local.path)

        fun record(local: LocalSaveFile, remote: RemoteSaveFile) =
            SaveSyncRecord(local.kind, local.path, remote.romId, remote.id, remote.updatedAt, local.size, local.modified, remote.size, remote.contentHash)

        /** RomM's `content_hash` of a save: the MD5 of its bytes, in hex. */
        fun md5(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
